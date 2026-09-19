package ru.gidravpn.hydra.vpn.core

import android.content.Context
import ru.gidravpn.hydra.data.model.ServerProfile
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
        val extra = runCatching { org.json.JSONObject(profile.extra) }.getOrDefault(org.json.JSONObject())
        val transport = profile.transport
        return buildList {
            add("--role"); add("client")
            add("--inbound"); add("socks5")
            add("--socks5"); add("127.0.0.1:$socksPort")
            add("--transport"); add(transport)
            if (transport == "oneme") {
                add("--maxToken"); add(extra.optString("maxToken"))
                add("--maxUid"); add(extra.optString("maxUid"))
            } else if (profile.address.isNotEmpty()) {
                add("--url"); add(profile.address)
            }
            extra.optString("codec").takeIf { it == "legacy" }?.let { add("--codec"); add(it) }
            if (profile.uuidOrPassword.isNotEmpty()) {
                val key = secretFile(ctx, "openflux.key", profile.uuidOrPassword, secrets)
                add("--encryption-key-file"); add(key.path)
            }
        }
    }
}
