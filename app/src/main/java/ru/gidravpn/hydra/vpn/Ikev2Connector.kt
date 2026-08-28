package ru.gidravpn.hydra.vpn

import android.content.Context
import android.net.Ikev2VpnProfile
import android.net.VpnManager
import android.os.Build
import androidx.annotation.RequiresApi
import ru.gidravpn.hydra.data.model.ServerProfile
import org.json.JSONObject

/**
 * Обёртка над системным IKEv2/IPsec (замена удалённому L2TP/IPsec).
 *
 * ВАЖНО (см. docs/PROTOCOLS.md):
 *  - Android 13+ полностью удалил стек L2TP; реализовать «настоящий» L2TP на уровне
 *    приложения нереалистично.
 *  - Начиная с Android 12/13 (VpnManager, API 33) приложение может провижионить
 *    IKEv2/IPsec-профиль. Это управляемый системой VPN, отдельный от нашего tun-сервиса.
 *  - Профиль хранит PSK/логин из ServerProfile.uuidOrPassword (PSK) или extra (user/pass).
 */
class Ikev2Connector(private val context: Context) {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun provisionAndStart(profile: ServerProfile) {
        val vpnManager = context.getSystemService(VpnManager::class.java)
            ?: error("VpnManager недоступен")

        val extra = runCatching { JSONObject(profile.extra) }.getOrDefault(JSONObject())
        val builder = Ikev2VpnProfile.Builder(profile.address, profile.address)

        when {
            extra.has("username") -> builder.setAuthUsernamePassword(
                extra.getString("username"),
                extra.getString("password"),
                /* serverRootCa = */ null
            )
            else -> builder.setAuthPsk(profile.uuidOrPassword.toByteArray())
        }

        builder.setBypassable(false)
        builder.setMetered(false)

        // Провижн: система покажет пользователю системный VPN-consent.
        vpnManager.provisionVpnProfile(builder.build())
        vpnManager.startProvisionedVpnProfileSession()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun stop() {
        context.getSystemService(VpnManager::class.java)?.stopProvisionedVpnProfile()
    }
}
