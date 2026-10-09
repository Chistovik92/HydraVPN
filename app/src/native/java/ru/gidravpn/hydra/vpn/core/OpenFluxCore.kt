package ru.gidravpn.hydra.vpn.core

import android.content.Context
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.subscription.OpenFluxArgs
import java.io.File

/**
 * OpenFlux (BETA) — TCP-туннель с подключаемыми транспортами (Yandex.Docs / Volga, MAX,
 * Cups.online, Mail.ru Docs). Апстрим — github.com/p1neappleXpress/OpenFlux (GPL-3.0);
 * официальный Android-клиент p1neappleXpress/OpenFluxAndroid запускает тот же бинарь так же —
 * подпроцессом из nativeLibraryDir в режиме `--role client --inbound socks5`.
 *
 * Схема — [SocksBridgeCore]: клиент → локальный SOCKS5 → sing-box → tun. Нужен exit-узел
 * (`openflux --role exit`) с тем же транспортом/документом; работает с любым режимом выхода
 * (`l3`/`l4`) — выбор на стороне узла. Не проверено с живым узлом.
 */
class OpenFluxCore : SocksBridgeCore() {
    override val name = "OpenFlux (beta)"
    override val binaryName = "libopenflux.so"
    override val socksPort = 10810
    override val label = "OpenFlux"
    override val buildScript = "scripts/build-openflux.sh"

    override fun prepare(ctx: Context, profile: ServerProfile, secrets: MutableList<File>): List<String> {
        val key = if (profile.uuidOrPassword.isNotEmpty()) secretFile(ctx, "openflux.key", profile.uuidOrPassword, secrets).path else null
        return OpenFluxArgs.build(profile, socksPort, key)
    }
}
