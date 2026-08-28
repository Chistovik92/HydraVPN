package ru.gidravpn.hydra

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import ru.gidravpn.hydra.ui.MainViewModel
import ru.gidravpn.hydra.ui.HydraRoot
import ru.gidravpn.hydra.ui.theme.HydraTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    // Системный VPN-consent
    private val vpnConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startTunnel()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Импорт по deep-link (vless:// и т.п.)
        handleImportIntent(intent)

        lifecycleScope.launch {
            vm.requestPermission.collect { requestVpnPermission() }
        }

        setContent {
            HydraTheme { HydraRoot(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }

    private fun handleImportIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if ("://" in data) vm.importLink(data)
    }

    private fun requestVpnPermission() {
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnConsent.launch(prepare) else vm.startTunnel()
    }
}
