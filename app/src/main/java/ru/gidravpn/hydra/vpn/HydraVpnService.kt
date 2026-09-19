package ru.gidravpn.hydra.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import ru.gidravpn.hydra.MainActivity
import ru.gidravpn.hydra.R
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.vpn.core.ConnectionState
import ru.gidravpn.hydra.vpn.core.CoreFactoryProvider
import ru.gidravpn.hydra.vpn.core.VpnCore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Основной туннель для всех движков (sing-box / Xray / amneziawg-go /
 * userspace-PPP: SSTP, L2TP). Поднимает tun-интерфейс и передаёт его
 * дескриптор в ядро, выбранное по протоколу профиля.
 *
 * Транспортные сокеты userspace-ядер (TLS для SSTP, UDP для L2TP)
 * выводятся из-под VPN-маршрутизации через [SocketGuard].
 */
class HydraVpnService : VpnService() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(ru.gidravpn.hydra.LocaleHelper.wrap(base))
    }

    // @Volatile: поля читаются и пишутся с разных потоков — корутины сервиса
    // живут на Dispatchers.IO (это пул, а не один поток), onStartCommand
    // приходит с главного, а колбэк смерти ядра (Фаза 7a) — вообще с потока
    // ядра (ридер SSTP/L2TP, JNI-поток sing-box, binder-поток :xray).
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var core: VpnCore? = null
    // Сырой fd tun-интерфейса, снятый ДО detachFd() (см. releaseTun()).
    @Volatile private var tunFd: Int = -1
    private var connectJob: Job? = null
    // Держит уведомление в актуальном состоянии по мере роста трафика.
    private var notifJob: Job? = null
    // true — tun держится поднятым намеренно, как «чёрная дыра» (Kill Switch),
    // см. enterKillSwitch().
    @Volatile private var killSwitchBlocking = false
    // true — ERROR получен разрывом уже поднятого туннеля, а не неудачным
    // подключением: заголовок уведомления у этих случаев разный.
    @Volatile private var tunnelDropped = false
    // Попытка, чей туннель сейчас реально поднят; null — ничего не поднято.
    // Смерть ядра принимается только от неё (см. onCoreDied()).
    @Volatile private var liveAttempt: Attempt? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Одна попытка подключения (Фаза 7a). Нужна, чтобы различить три случая,
     * которые иначе выглядят одинаково — «ядро сообщило, что умерло»:
     *  - умерло у живого туннеля  → гасим/блокируем, меняем состояние;
     *  - умерло прямо внутри `start()`, ещё до CONNECTED → это обычная ошибка
     *    подключения, её обрабатывает существующий catch в doConnect();
     *  - эхо уже проигравшей попытки (переключение сервера — это новый
     *    doConnect поверх старого) → трогать текущий туннель нельзя.
     */
    private class Attempt {
        @Volatile var died: String? = null
        val handled = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    companion object {
        const val ACTION_CONNECT = "ru.gidravpn.hydra.CONNECT"
        const val ACTION_DISCONNECT = "ru.gidravpn.hydra.DISCONNECT"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL_ID = "vpn_status"
        private const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        SocketGuard.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { stopTunnel(); return START_NOT_STICKY }
            else -> {
                val serverId = intent?.getLongExtra(EXTRA_SERVER_ID, -1) ?: -1
                // Фаза 7e: startForeground может отказать (на Android 12+ —
                // ForegroundServiceStartNotAllowedException, если сервис
                // подняли из фона без разрешённого повода). Голый вызов ронял
                // процесс; лучше честно отказаться от подключения.
                val foreground = runCatching {
                    startForeground(NOTIF_ID, buildNotification(ConnectionState.CONNECTING, "…"))
                }
                if (foreground.isFailure) {
                    VpnState.log("Ошибка: система не дала поднять сервис в foreground — " +
                        "${foreground.exceptionOrNull()?.javaClass?.simpleName}")
                    VpnState.state.value = ConnectionState.ERROR
                    stopSelf()
                    return START_NOT_STICKY
                }
                connect(serverId)
            }
        }
        return START_STICKY
    }

    private fun connect(serverId: Long) {
        connectJob?.cancel()
        connectJob = scope.launch { doConnect(serverId) }
    }

    private suspend fun doConnect(serverId: Long) {
        // Переключение на другой сервер при уже активном соединении вызывает
        // connect() повторно — без явного гашения предыдущих core/tun они
        // просто перезаписываются полями ниже и утекают активными в фоне
        // (соединение остаётся поднятым, невидимым для UI/disconnect).
        releaseTun()

        // fd/ядро держим в локальных переменных, пока попытка не подтверждена
        // активной, — иначе гонка с параллельным stopTunnel()/новым connect()
        // (оба просто отдельные scope.launch{} на Dispatchers.IO) может
        // записать их в поля класса уже ПОСЛЕ того, как отключение решило,
        // что core/tun пусты, и просто ничего не остановило. cancel() при этом
        // не прерывает уже идущий синхронный vpnCore.start() — отмена
        // проверяется явно через ensureActive() сразу после него.
        var newTun: ParcelFileDescriptor? = null
        var newTunFd = -1
        var newCore: VpnCore? = null
        val attempt = Attempt()
        try {
            VpnState.state.value = ConnectionState.CONNECTING
            val settings = ru.gidravpn.hydra.data.repository.VpnSettingsRepository(applicationContext)
            // serverId < 0 — перезапуск системой по START_STICKY (intent == null):
            // поднимаем то же, что было, а не «Сервер #-1 не найден».
            val id = if (serverId >= 0) serverId else settings.lastServerId.firstOrNull() ?: -1
            val profile = ru.gidravpn.hydra.data.repository.ServerRepository(applicationContext).byId(id)
                ?: throw IllegalStateException("Сервер #$id не найден")

            VpnState.activeServer.value = profile
            VpnState.log("Подключение к ${profile.address}…")

            newTun = establishTun(profile)
            newTunFd = newTun.fd // снимаем ДО openTun()/detachFd() внутри vpnCore.start()
            val vpnCore = CoreFactoryProvider.factory.create(profile)
            newCore = vpnCore
            VpnState.log("Ядро: ${vpnCore.name}")

            // Фаза 7a: слушатель ставится ДО start() — ядро может развалиться
            // уже внутри него (PPP не согласовался, :xray умер), и этот случай
            // должен попасть в общий catch ниже, а не потеряться.
            vpnCore.setDeathListener { reason -> onCoreDied(attempt, reason) }

            vpnCore.start(
                tun = newTun,
                profile = profile,
                onLog = { VpnState.log(it) },
                onStats = { VpnState.stats.value = it },
            )

            currentCoroutineContext().ensureActive()
            // start() вернулся успешно, но ядро уже успело сообщить о смерти —
            // «поднятым» такой туннель считать нельзя.
            attempt.died?.let { throw IllegalStateException("ядро остановилось сразу после запуска: $it") }

            tun = newTun
            tunFd = newTunFd
            core = newCore
            // С этого момента onCoreDied() принимает смерть этой попытки.
            liveAttempt = attempt
            VpnState.connectedSince.value = System.currentTimeMillis()
            VpnState.state.value = ConnectionState.CONNECTED
            VpnState.log("✓ Соединение установлено")
            // Плитка/автозагрузка берут отсюда сервер — в т.ч. когда в UI его явно
            // не выбирали (кнопка «Подключить» берёт первый из списка).
            settings.setLastServerId(profile.id)
            updateNotification(ConnectionState.CONNECTED, profile.name)

            // StateFlow не эмитит одинаковые значения, так что при нулевом
            // трафике уведомление не перерисовывается впустую.
            notifJob?.cancel()
            notifJob = scope.launch {
                VpnState.stats.collect { updateNotification(ConnectionState.CONNECTED, profile.name) }
            }

            // Ядро могло умереть в окне между проверкой выше и `liveAttempt =
            // attempt`: тогда onCoreDied() отбросил событие как «эхо чужой
            // попытки», и без этой перепроверки туннель остался бы зомби.
            attempt.died?.let { handleCoreDeath(attempt, it) }
        } catch (t: Throwable) {
            runCatching { newCore?.stop() }

            // Отмена (новый connect()/stopTunnel() уже победили гонку за эти же
            // локальные newTun/newCore) — не Kill Switch: закрываем свою половину
            // ресурсов как раньше и уходим, ничего не решая за победившую сторону.
            //
            // Ровно один из двух путей ниже, не оба: если vpnCore.start() успел
            // detachFd() тот же fd (сделает почти любое нативное ядро сразу
            // в openTun()), newTun больше им не владеет — close() на нём
            // безопасный no-op, а закрывать нужно raw fd через adoptFd().
            // Если же ядро упало РАНЬШЕ detachFd() (например, XrayCore на
            // таймауте bindService к процессу :xray) — newTun ещё владеет
            // дескриптором, и adoptFd() того же номера ПОСЛЕ newTun.close()
            // цепляет уже закрытый (и потенциально переиспользованный ядром
            // fd) номер — fdsan валит процесс SIGABRT
            // ("failed to exchange ownership... was expected to be unowned").
            if (t is CancellationException) {
                if (newTun?.fileDescriptor?.valid() == true) {
                    runCatching { newTun.close() }
                } else if (newTunFd >= 0) {
                    runCatching { ParcelFileDescriptor.adoptFd(newTunFd).close() }
                }
                throw t
            }

            // У DeadObjectException и части NPE сообщения нет — без имени класса
            // в логе оставалось неразбираемое «Ошибка: null».
            VpnState.log("Ошибка: ${t.message ?: t.javaClass.simpleName}")

            // Kill Switch: только если tun реально поднялся (establishTun()
            // успел отработать) — иначе блокировать нечем, ведём себя как раньше.
            val killSwitch = newTun != null && runCatching {
                ru.gidravpn.hydra.data.repository.VpnSettingsRepository(applicationContext)
                    .killSwitch.firstOrNull()
            }.getOrNull() == true

            if (killSwitch) {
                enterKillSwitch(newTun, newTunFd, "тоннель не поднялся")
            } else {
                if (newTun?.fileDescriptor?.valid() == true) {
                    runCatching { newTun.close() }
                } else if (newTunFd >= 0) {
                    runCatching { ParcelFileDescriptor.adoptFd(newTunFd).close() }
                }
                VpnState.state.value = ConnectionState.ERROR
                stopTunnel()
            }
        }
    }

    /**
     * Kill Switch: держим tun поднятым как «чёрную дыру». 0.0.0.0/0 по-прежнему
     * маршрутизируется в этот fd (addRoute в establishTun), но ядро уже
     * остановлено и больше ничего туда не пишет/не читает — трафик молча
     * дропается вместо утечки в открытую сеть напрямую. Гасит это состояние
     * только явный ACTION_DISCONNECT (releaseTun()).
     *
     * Вызывается из двух мест: неудачное подключение (catch в doConnect) и,
     * с Фазы 7a, смерть ядра у уже поднятого туннеля (handleCoreDeath) —
     * до 7a разрыв живого соединения Kill Switch не защищал вообще.
     *
     * Честная оговорка: если ядро успело detachFd() СЕБЕ и упало уже ПОСЛЕ
     * этого (не до), сырой fd мог быть закрыт/переиспользован самим ядром при
     * крахе — в этом редком случае tun фактически не защищает, потому что
     * системе больше нечего держать поднятым. Не проверено на реальном
     * устройстве — см. CHANGELOG.
     */
    private fun enterKillSwitch(blockingTun: ParcelFileDescriptor?, blockingTunFd: Int, reason: String) {
        tun = blockingTun
        tunFd = blockingTunFd
        core = null
        killSwitchBlocking = true
        VpnState.state.value = ConnectionState.ERROR
        VpnState.activeServer.value = null
        VpnState.connectedSince.value = 0L
        VpnState.log("Kill Switch: трафик заблокирован ($reason)")
        updateNotification(ConnectionState.ERROR, getString(R.string.tile_blocked))
    }

    /**
     * Колбэк ядра «я умер» (Фаза 7a). Зовётся с потока ядра — ридера SSTP/L2TP,
     * JNI-потока sing-box, binder-потока `:xray`, — поэтому здесь только
     * отметка и передача работы в scope сервиса.
     *
     * Событие принимается только от попытки, чей туннель сейчас поднят:
     * смерть внутри start() ещё до CONNECTED разбирает catch в doConnect
     * (по [Attempt.died]), а эхо проигравшей попытки трогать живой туннель
     * не должно.
     */
    private fun onCoreDied(attempt: Attempt, reason: String) {
        attempt.died = reason
        if (attempt !== liveAttempt) return
        scope.launch { handleCoreDeath(attempt, reason) }
    }

    /** Разрыв уже поднятого туннеля: гасим ядро и либо блокируем трафик, либо честно отключаемся. */
    private suspend fun handleCoreDeath(attempt: Attempt, reason: String) {
        // Ядро вправе сообщить о смерти и несколько раз (упал ридер, следом
        // закрылся PPP) — обрабатываем ровно один раз.
        if (!attempt.handled.compareAndSet(false, true)) return
        if (attempt !== liveAttempt) return
        liveAttempt = null

        VpnState.log("Туннель разорван: $reason")
        tunnelDropped = true
        notifJob?.cancel()
        notifJob = null
        val deadTun = tun
        val deadTunFd = tunFd
        runCatching { core?.stop() }
        core = null

        val killSwitch = runCatching {
            ru.gidravpn.hydra.data.repository.VpnSettingsRepository(applicationContext)
                .killSwitch.firstOrNull()
        }.getOrNull() == true

        if (killSwitch && deadTun != null) {
            enterKillSwitch(deadTun, deadTunFd, "туннель разорван")
        } else {
            // Без Kill Switch держать tun незачем: ядра за ним больше нет,
            // и «чёрная дыра» только маскировала бы разрыв.
            tun = null
            tunFd = -1
            if (deadTun?.fileDescriptor?.valid() == true) {
                runCatching { deadTun.close() }
            } else if (deadTunFd >= 0) {
                runCatching { ParcelFileDescriptor.adoptFd(deadTunFd).close() }
            }
            VpnState.state.value = ConnectionState.ERROR
            VpnState.activeServer.value = null
            VpnState.connectedSince.value = 0L
            updateNotification(ConnectionState.ERROR, getString(R.string.notif_conn_dropped))
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    /**
     * Гасит текущее ядро/tun. sing-box (openTun -> detachFd()) забирает
     * реальный fd себе — после этого ParcelFileDescriptor.close() с нашей
     * стороны становится no-op, а core.stop() у libbox не всегда надёжно/
     * синхронно закрывает fd на своей стороне: воспроизведено на реальном
     * устройстве — VPN-сеть системы (dumpsys connectivity) оставалась
     * CONNECTED ещё десятки секунд после штатного отключения в UI, пока не
     * убивался процесс целиком (что закрывает вообще все fd процесса).
     * Подстраховываемся, закрывая сырой fd явно через adoptFd(...).close().
     */
    private fun releaseTun() {
        notifJob?.cancel()
        notifJob = null
        killSwitchBlocking = false
        tunnelDropped = false
        // Штатная остановка — не смерть: колбэк уже снятого ядра не должен
        // потом уронить состояние нового подключения.
        liveAttempt = null
        runCatching { core?.stop() }
        core = null
        runCatching { tun?.close() }
        tun = null
        if (tunFd >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(tunFd).close() }
            tunFd = -1
        }
    }

    private suspend fun establishTun(profile: ServerProfile): ParcelFileDescriptor {
        // PPP-движки (SSTP/L2TP): MRU 1400, иначе фрагментация на TLS/UDP-транспорте
        val userspace = profile.protocol?.engine == ru.gidravpn.hydra.data.model.Engine.USERSPACE
        // Должен совпадать с tun-инбаундом sing-box (тот же RoutingRepository.mtu).
        val mtu = (ru.gidravpn.hydra.data.repository.RoutingRepository(applicationContext).mtu.firstOrNull()
            ?: ru.gidravpn.hydra.data.model.MtuPreset.AUTO).value
        val builder = Builder()
            .setSession("Hydra")
            .setMtu(if (userspace) minOf(1400, mtu) else mtu)
            .addAddress("172.19.0.1", 28)
            .addDnsServer("1.1.1.1")
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)

        // Раздельное туннелирование (DataStore → правила VpnService.Builder)
        val split = ru.gidravpn.hydra.data.repository.SplitTunnelRepository(applicationContext)
            .settings.firstOrNull() ?: ru.gidravpn.hydra.data.model.SplitTunnel()
        when (split.mode) {
            ru.gidravpn.hydra.data.model.SplitTunnelMode.INCLUDE ->
                split.packages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            ru.gidravpn.hydra.data.model.SplitTunnelMode.EXCLUDE -> {
                split.packages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
                runCatching { builder.addDisallowedApplication(packageName) } // собственный трафик — мимо VPN
            }
            ru.gidravpn.hydra.data.model.SplitTunnelMode.OFF ->
                runCatching { builder.addDisallowedApplication(packageName) } // не заворачиваем собственный трафик
        }

        return builder.establish() ?: error("Не удалось поднять tun (нет разрешения VPN?)")
    }

    private fun stopTunnel() {
        connectJob?.cancel()
        connectJob = null
        scope.launch {
            releaseTun()
            VpnState.state.value = ConnectionState.DISCONNECTED
            VpnState.activeServer.value = null
            VpnState.connectedSince.value = 0L
            VpnState.log("Соединение разорвано")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() { stopTunnel() }
    override fun onDestroy() { SocketGuard.detach(); scope.cancel(); super.onDestroy() }

    // ----- notification -----
    private fun buildNotification(state: ConnectionState, server: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            state == ConnectionState.CONNECTED -> getString(R.string.notif_encrypted)
            state == ConnectionState.CONNECTING -> getString(R.string.connecting)
            state == ConnectionState.ERROR && killSwitchBlocking -> getString(R.string.notif_killswitch)
            state == ConnectionState.ERROR && tunnelDropped -> getString(R.string.notif_dropped)
            state == ConnectionState.ERROR -> getString(R.string.notif_error)
            else -> getString(R.string.disconnected)
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)

        if (state == ConnectionState.CONNECTED) {
            val s = VpnState.stats.value
            // Строка трафика имеет смысл только когда счётчики ненулевые;
            // иначе показываем один сервер, чтобы не рисовать «0 B / 0 B».
            val traffic = if (s.downBytes > 0 || s.upBytes > 0) {
                "  ·  ↓ ${humanBytes(s.downBytes)}  ↑ ${humanBytes(s.upBytes)}"
            } else ""
            b.setContentText("$server$traffic")

            val disconnect = PendingIntent.getService(
                this, 1,
                Intent(this, HydraVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE
            )
            val servers = PendingIntent.getActivity(
                this, 2,
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SERVERS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, getString(R.string.notif_action_disconnect), disconnect)
            b.addAction(0, getString(R.string.notif_action_servers), servers)
        } else if (state == ConnectionState.ERROR && killSwitchBlocking) {
            b.setContentText(server)
            val disconnect = PendingIntent.getService(
                this, 1,
                Intent(this, HydraVpnService::class.java).setAction(ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(0, getString(R.string.notif_action_disconnect), disconnect)
        } else {
            b.setContentText(server)
        }
        return b.build()
    }

    private fun updateNotification(state: ConnectionState, server: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(state, server))
    }

    /** Байты в человекочитаемый вид: «0,0 MB» для килобайт выглядело как поломка. */
    private fun humanBytes(bytes: Long): String = ru.gidravpn.hydra.ui.components.humanBytes(bytes)
}
