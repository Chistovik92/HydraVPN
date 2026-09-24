package ru.gidravpn.hydra

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.gidravpn.hydra.data.log.LogStore
import ru.gidravpn.hydra.data.repository.LogSettingsRepository

class HydraApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppCtx.filesDir = filesDir
        AppCtx.appContext = this

        // Application создаётся и в процессе :xray (XrayEngineService). DataStore
        // нельзя открывать на тот же файл из двух процессов, а логи там не пишутся.
        if (currentProcessName().contains(':')) return

        LogStore.init(filesDir)
        runCatching { ru.gidravpn.hydra.data.work.SubscriptionUpdateWorker.schedule(this) }
        installCrashHandler()
        val logSettings = LogSettingsRepository(this)
        appScope.launch { logSettings.mode.collect { LogStore.mode = it } }
        appScope.launch { logSettings.retention.collect { LogStore.retention = it } }

        // Виджет на рабочем столе (Фаза 8) перерисовывается при каждой смене состояния туннеля:
        // его кнопка — прямой PendingIntent на сервис, и он должен соответствовать состоянию.
        appScope.launch {
            kotlinx.coroutines.flow.combine(ru.gidravpn.hydra.vpn.VpnState.state, ru.gidravpn.hydra.vpn.VpnState.activeServer) { s, srv -> s to srv }
                .collect { (s, srv) -> runCatching { ru.gidravpn.hydra.vpn.HydraWidget.update(this@HydraApp, s, srv ?: ru.gidravpn.hydra.vpn.HydraWidget.lastServer(this@HydraApp)) } }
        }
    }

    /**
     * Падение любого нашего потока — в файл лога (Фаза 7e).
     *
     * В приложении несколько ручных потоков (`sstp-reader`, `l2tp-reader`,
     * `tun-ppp-read`, поток статистики моста): исключение в любом из них
     * уносит процесс, и разбирать потом нечего — logcat к моменту жалобы
     * пользователя давно прокрутился, а экран логов показывает только то, что
     * успели сохранить. Пишем стек в [LogStore] уровнем ERROR (он сохраняется
     * даже в режиме «только ошибки», выставленном по умолчанию) и передаём
     * управление системному обработчику — поведение самого краха не меняем.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val stack = java.io.StringWriter().also { w ->
                    error.printStackTrace(java.io.PrintWriter(w))
                }.toString()
                LogStore.append(
                    "Ошибка: аварийное завершение (поток ${thread.name})\n$stack",
                    ru.gidravpn.hydra.data.log.LogLevel.ERROR,
                )
                // Очередь записи асинхронная, а процесс сейчас умрёт — даём
                // потоку записи успеть добраться до диска.
                LogStore.flushBlocking(2000)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun currentProcessName(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }
            .getOrDefault(packageName)
}
