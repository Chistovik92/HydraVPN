package ru.gidravpn.hydra.vpn.core

import android.os.ParcelFileDescriptor
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.WireGuardConfigBuilder

/**
 * AmneziaWG (обфусцированный WireGuard), версии 1.0 / 1.5 / 2.0.
 *
 * Движок: `amneziawg-go.aar` (форк wireguard-go от Amnezia, gomobile-биндинг).
 * Отличие от обычного WireGuard — параметры обфускации в [Interface]:
 *  - 1.0/1.5: Jc, Jmin, Jmax, S1, S2, H1–H4 (мусорные пакеты);
 *  - 2.0:     I1–I5 (маркеры заголовков вместо init/response-пакетов).
 *
 * Генерация .conf и uapi — [WireGuardConfigBuilder] (готова и покрыта).
 * Для запуска туннеля положите `amneziawg-go.aar` в `app/libs` (см. docs/BUILD.md)
 * и подключите его GoBackend/IpcUapi-вызовы в местах, помеченных TODO.
 */
class AmneziaWgCore : VpnCore {

    override val name = "AmneziaWG (amneziawg-go)"

    @Volatile private var running = false

    override fun start(
        tun: ParcelFileDescriptor,
        profile: ServerProfile,
        onLog: (String) -> Unit,
        onStats: (TrafficStats) -> Unit,
    ) {
        val conf = WireGuardConfigBuilder.buildConf(profile)
        val uapi = WireGuardConfigBuilder.buildUapi(profile)
        val version = runCatching { org.json.JSONObject(profile.extra) }
            .getOrDefault(org.json.JSONObject()).optString("awg_version", "1.0")

        onLog("AmneziaWG: версия профиля $version")
        onLog("AmneziaWG: конфиг сгенерирован (${conf.length} байт), uapi — ${uapi.lineSequence().count()} строк")

        // TODO(amneziawg-go.aar): интеграция после сборки .aar:
        //   1. wireguard-android GoBackend из amneziawg-go.aar принимает Config
        //      (org.json → com.wireguard.config.Config.parse) ЛИБО
        //   2. прямой uapi: IpcUapi.setUapi(tunnelHandle, WireGuardConfigBuilder.buildUapi(profile))
        //   3. tun-fd передаётся бэкенду, как в SingBoxCore (detachFd).
        running = true
        throw NotImplementedError(
            "AmneziaWG: положите amneziawg-go.aar в app/libs и подключите GoBackend (docs/BUILD.md)"
        )
    }

    override fun stop() {
        running = false
        // TODO(amneziawg-go.aar): GoBackend.shutdown()
    }
}
