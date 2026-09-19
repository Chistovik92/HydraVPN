package ru.gidravpn.hydra.vpn.core

import android.content.Context
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.OlcRtcConfigBuilder
import java.io.File

/**
 * olcRTC (BETA) — TCP поверх WebRTC через сервисы видеозвонков (Jitsi / Телемост / WB Stream).
 * Апстрим — github.com/openlibrecommunity/olcrtc (WTFPL), **архивирован 14.09.2026**: автор
 * переносит разработку в snolc в другом виде. Пока клиент работает как есть — собран из
 * последнего состояния репозитория (scripts/build-olcrtc.sh).
 *
 * Схема — [SocksBridgeCore]: `olcrtc <config.yaml>` (режим `cnc`) → локальный SOCKS5 → sing-box.
 * Нужен сервер `olcrtc srv` с тем же ключом и комнатой. Не проверено с живым сервером.
 */
class OlcRtcCore : SocksBridgeCore() {
    override val name = "olcRTC (WebRTC, beta)"
    override val binaryName = "libolcrtc.so"
    override val socksPort = 10809
    override val label = "olcRTC"
    override val buildScript = "scripts/build-olcrtc.sh"

    override fun prepare(ctx: Context, profile: ServerProfile, secrets: MutableList<File>): List<String> {
        val cfg = secretFile(ctx, "olcrtc-client.yaml", OlcRtcConfigBuilder.build(profile, socksPort), secrets)
        return listOf(cfg.path)
    }
}
