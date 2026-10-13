package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.servers.AppProxy
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Собственный трафик Hydra (SSH «Моих серверов», подписки, обновления) через VPN (0.8.0-rc3).
 *
 * Режим TUN: процесс Hydra больше не исключается из tun — отдельного кода не нужно. Режим «прокси»: пока ядро поднято, всё, что Hydra
 * открывает по сети (HTTP-клиенты берут [selector] по умолчанию, SSH — [AppProxy]), идёт в локальный прокси ядра `127.0.0.1:порт`.
 * Без этого сервер, закрытый без VPN, не открывался и при включённом VPN. Локальные адреса всегда напрямую.
 */
object AppNet {
    @Volatile private var proxyPort = 0

    /** Включить: соединения Hydra пойдут через локальный прокси на [port]. */
    fun use(port: Int) { proxyPort = port; AppProxy.port = port }

    /** Выключить (ядро остановлено): снова напрямую. */
    fun clear() { proxyPort = 0; AppProxy.port = 0 }

    val active get() = proxyPort > 0

    val selector: ProxySelector = object : ProxySelector() {
        private val previous: ProxySelector? = runCatching { getDefault() }.getOrNull()
        override fun select(uri: URI?): List<Proxy> {
            val port = proxyPort
            val host = uri?.host.orEmpty()
            if (port > 0 && host.isNotEmpty() && !isLocal(host)) return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            return previous?.takeIf { it !== this }?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    /** Один раз при старте, до создания HTTP-клиентов: OkHttp запоминает селектор при создании. */
    fun install() { ProxySelector.setDefault(selector) }

    internal fun isLocal(host: String): Boolean =
        host.equals("localhost", true) || host == "::1" || host == "[::1]" || host.startsWith("127.")
}
