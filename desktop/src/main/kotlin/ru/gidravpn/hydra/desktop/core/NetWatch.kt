package ru.gidravpn.hydra.desktop.core

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Смена физической сети на ПК (0.7.9): Wi-Fi ↔ кабель ↔ модем, новый адрес от DHCP.
 *
 * Зачем: Xray ходит к серверу через `direct` sing-box (процесс исключён из туннеля), а sing-box
 * сам — через сокеты, привязанные к прежнему интерфейсу. После смены сети такие соединения
 * умирают молча: RST не приходит, keep-alive sing-box 1.12 — 10 минут (поле `tcp_keep_alive`
 * появилось только в 1.13). Программы «висят», пока пользователь не переподключится руками.
 * Отпечаток сравнивается раз в несколько секунд; изменился и устоялся — переподключаемся.
 */
object NetWatch {
    /** Интерфейсы, поднятые с IPv4 (без петли, туннелей и link-local), — «имя=адреса;…». Пусто — сети нет. */
    fun fingerprint(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !isTunnel(it) }.getOrDefault(false) }
            .mapNotNull { ni ->
                val v4 = ni.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filterNot { it.isLinkLocalAddress }.mapNotNull { it.hostAddress }.sorted()
                if (v4.isEmpty()) null else "${ni.name}=${v4.joinToString(",")}"
            }
            .sorted().joinToString(";")
    }.getOrDefault("")

    /** Свой TUN (Hydra / hydra0 / utunN) и чужие VPN-адаптеры — не «физическая сеть». */
    private fun isTunnel(ni: NetworkInterface): Boolean {
        val n = ni.name.lowercase()
        val d = ni.displayName.orEmpty().lowercase()
        return n.startsWith("utun") || n.startsWith("tun") || n.startsWith("hydra") || n.startsWith("wg") ||
            listOf("hydra", "wintun", "wireguard", "tap-windows", "openvpn").any { it in d }
    }
}
