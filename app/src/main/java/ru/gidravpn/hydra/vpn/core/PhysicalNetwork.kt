package ru.gidravpn.hydra.vpn.core

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Выбор «физической» сети под VPN: с интернетом и не сам VPN. Нужен, когда
 * нельзя ждать колбэк (первые доли секунды после старта sing-box, №9), для
 * `VpnService.setUnderlyingNetworks()` (7c) и для монитора интерфейса sing-box
 * при смене сети (0.7.9).
 */
object PhysicalNetwork {
    /**
     * @param exclude сеть, о потере которой только что сообщил колбэк: в момент `onLost` система
     *   ещё какое-то время отдаёт её и как activeNetwork, и в allNetworks (проверено на эмуляторе:
     *   ядро привязывалось к уже исчезнувшему wlan0 и оставалось без сети до переподключения).
     */
    fun pick(cm: ConnectivityManager, exclude: Network? = null): Network? {
        // Наш пакет исключён из VPN (addDisallowedApplication в establishTun), поэтому
        // activeNetwork для него — та сеть, которую система сейчас считает основной
        // (Wi-Fi, а при его потере — мобильная). VPN он вернёт, только если исключение
        // не сработало, — тогда идём по списку.
        cm.activeNetwork?.takeIf { it != exclude && usable(cm, it) }?.let { return it }

        // allNetworks объявлен устаревшим и однажды перестанет отдавать полный
        // список; другого способа увидеть все сети у нас нет — берём через @Suppress.
        @Suppress("DEPRECATION")
        val candidates = cm.allNetworks.filter { it != exclude && usable(cm, it) }
        // Раньше брался просто первый проверенный из списка — порядок там произвольный,
        // и при живых Wi-Fi и мобильной ядро могло уйти в мобильную. Теперь: проверенная
        // сеть, затем Wi-Fi → Ethernet → мобильная → прочее.
        return candidates.maxByOrNull { n -> score(cm.getNetworkCapabilities(n)) }
    }

    /** Есть интернет, это не VPN и у сети есть живой сетевой интерфейс (не уже снятый системой). */
    private fun usable(cm: ConnectivityManager, n: Network): Boolean {
        val c = cm.getNetworkCapabilities(n) ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        val name = cm.getLinkProperties(n)?.interfaceName ?: return false
        return runCatching { java.net.NetworkInterface.getByName(name)?.isUp == true }.getOrDefault(false)
    }

    private fun score(c: NetworkCapabilities?): Int {
        if (c == null) return 0
        var s = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 100 else 0
        s += when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 30
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 20
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 10
            else -> 0
        }
        return s
    }
}
