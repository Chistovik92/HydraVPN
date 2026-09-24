package ru.gidravpn.hydra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.HydraRoot
import ru.gidravpn.hydra.ui.LockScreen
import ru.gidravpn.hydra.ui.theme.HydraTheme
import kotlinx.coroutines.launch

// FragmentActivity (Фаза 8), а не ComponentActivity: BiometricPrompt живёт на фрагментах.
class MainActivity : FragmentActivity() {

    companion object {
        /** Открыть вкладку «Серверы» — из кнопки в уведомлении. */
        const val EXTRA_OPEN_SERVERS = "open_servers"

        /** App Shortcuts (res/xml/shortcuts.xml). */
        const val ACTION_TOGGLE = "ru.gidravpn.hydra.action.TOGGLE"
        const val ACTION_SERVERS = "ru.gidravpn.hydra.action.SERVERS"

        /** Сколько приложение может пробыть в фоне, прежде чем снова спросить отпечаток/PIN. */
        private const val RELOCK_AFTER_MS = 30_000L

        private const val AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        /**
         * Есть ли на устройстве чем разблокировать (отпечаток, лицо или хотя бы PIN/пароль экрана).
         * Только Android 9+: на 8.x androidx.biometric рисует свой диалог отпечатка через
         * AppCompat AlertDialog, а тема Hydra не AppCompat — диалог упал бы при показе.
         */
        fun canLock(ctx: android.content.Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private val vm: MainViewModel by viewModels()

    // Разовый сигнал «открыть Серверы»; сбрасывается после применения.
    private var openServers by mutableStateOf(false)

    // Блокировка приложения (Фаза 8). unlocked переживает поворот экрана через savedInstanceState.
    private var unlocked by mutableStateOf(false)
    private var stoppedAt = 0L

    // Системный VPN-consent
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startTunnel()
    }

    // Разрешение на уведомления (Android 13+) — без него не видно статус VPN-соединения
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* отказ не критичен: сервис всё равно поднимет туннель */ }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestNotificationPermissionIfNeeded()
        unlocked = savedInstanceState?.getBoolean("unlocked") == true

        // Импорт по deep-link (vless:// и т.п.) и ярлыки на иконке.
        handleIntent(intent)

        // Только на холодном старте процесса — иначе пересоздание Activity
        // (поворот, смена ярлыка и т.п.) пыталось бы подключаться повторно.
        if (savedInstanceState == null) {
            vm.autoConnectIfEnabled()
            vm.checkForUpdates(manual = false)
        }

        lifecycleScope.launch {
            vm.requestPermission.collect { requestVpnPermission() }
        }
        // При включённой блокировке экран приложения не попадает в скриншоты и в превью
        // «Недавних» — иначе блокировка не имела бы смысла.
        lifecycleScope.launch {
            vm.appLock.collect { lock ->
                if (lock == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        openServers = intent?.getBooleanExtra(EXTRA_OPEN_SERVERS, false) == true ||
            intent?.action == ACTION_SERVERS

        setContent {
            val themeMode by vm.themeMode.collectAsState()
            val lock by vm.appLock.collectAsState()
            HydraTheme(themeMode) {
                when {
                    lock == null -> LockScreen(onUnlock = null)          // настройка ещё читается
                    lock == true && !unlocked -> LockScreen(onUnlock = ::authenticate)
                    else -> HydraRoot(vm, openServers = openServers, onOpenServersHandled = { openServers = false })
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("unlocked", unlocked)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt > RELOCK_AFTER_MS) unlocked = false
    }

    private fun authenticate() {
        // Нечем разблокировать (на устройстве сняли и отпечатки, и PIN) — не запираем владельца
        // в его же приложении: пускаем и честно пишем в журнал.
        if (!canLock(this)) {
            ru.gidravpn.hydra.vpn.VpnState.log("Блокировка приложения: на устройстве нет отпечатка/PIN — вход без проверки")
            unlocked = true
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            })
        runCatching {
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.lock_prompt_title))
                    .setSubtitle(getString(R.string.lock_prompt_subtitle))
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()
            )
        }.onFailure { ru.gidravpn.hydra.vpn.VpnState.log("Ошибка: BiometricPrompt — ${it.javaClass.simpleName}") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SERVERS, false) || intent.action == ACTION_SERVERS) openServers = true
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            // Ярлык «Подключить / Отключить» — тот же путь, что у большой кнопки (с VPN-согласием).
            ACTION_TOGGLE -> { vm.toggle(); return }
            ACTION_SERVERS -> return
        }
        // «Поделиться» (0.6.18): текст со ссылкой/подпиской или картинка с QR.
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                vm.importAuto(listOf(it)); return
            }
            @Suppress("DEPRECATION")
            val stream: android.net.Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            stream?.let { vm.importFromImage(it) }
            return
        }
        val data = intent?.data?.toString() ?: return
        if ("://" in data) vm.importAuto(listOf(data))
    }

    private fun requestVpnPermission() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else vm.startTunnel()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
