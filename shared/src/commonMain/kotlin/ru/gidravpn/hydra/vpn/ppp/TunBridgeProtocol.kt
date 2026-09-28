package ru.gidravpn.hydra.vpn.ppp

import ru.gidravpn.hydra.vpn.core.TrafficStats

/**
 * Протокольная часть TunBridge (NAT/чек-суммы) — переносимая на KMP.
 *
 * Туннель поднимается сервисом с адресом-заглушкой [tunLocalIp] (172.19.0.1);
 * реальный IP выдаётся позже по IPCP. Поэтому:
 *  - исходящие пакеты: src 172.19.0.1 → assignedIp (SNAT);
 *  - входящие пакеты:  dst assignedIp → 172.19.0.1 (обратный DNAT),
 * иначе ОС не примет их на tun.
 *
 * При перезаписи адресов корректируются чек-суммы IPv4-заголовка и
 * TCP/UDP (псевдозаголовок содержит IP-адреса) — инкрементально (RFC 1624).
 * IPv6 через PPP не поддерживается (только IPv4).
 */
class TunBridgeProtocol(
    private val tunLocalIp: String = "172.19.0.1",
    private val onStats: (TrafficStats) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    // Счётчики пишут два потока (tun→PPP и ридер транспорта), читает поток статистики: атомики.
    private val downBytes = java.util.concurrent.atomic.AtomicLong()
    private val upBytes = java.util.concurrent.atomic.AtomicLong()
    private var running = false
    private var localIpInt = 0
    private var tunIpInt = 0

    /** Запускает мост после завершения IPCP (assignedIp известен). */
    fun start(assignedIp: String) {
        if (running) return
        localIpInt = ipToInt(assignedIp)
        tunIpInt = ipToInt(tunLocalIp)
        running = true
        onLog("TunBridge: SNAT $tunLocalIp ⇄ $assignedIp")
    }

    /** Останавливает мост. */
    fun stop() {
        running = false
    }

    /** Проверяет, запущен ли мост. */
    fun isRunning(): Boolean = running

    /**
     * Обрабатывает исходящий пакет (tun → PPP).
     * Возвращает пакет с перезаписанным src IP или null, если пакет не нужно пересылать.
     */
    fun processOutgoing(packet: ByteArray): ByteArray? {
        if (!running || !isIpv4(packet)) return null
        val src = readInt(packet, 12)
        if (src != tunIpInt) return packet            // не наш источник — пропускаем как есть
        val p = packet.copyOf(packet.size)
        writeInt(p, 12, localIpInt)
        fixChecksums(p, src, localIpInt)
        upBytes.addAndGet(p.size.toLong())
        return p
    }

    /**
     * Обрабатывает входящий пакет (PPP → tun).
     * Возвращает пакет с перезаписанным dst IP или null, если пакет не нужно пересылать.
     */
    fun processIncoming(packet: ByteArray): ByteArray? {
        if (!running || !isIpv4(packet)) return null
        val dst = readInt(packet, 16)
        if (dst != localIpInt) return packet          // адресован не tun — как есть
        val p = packet.copyOf(packet.size)
        writeInt(p, 16, tunIpInt)
        fixChecksums(p, localIpInt, tunIpInt)
        downBytes.addAndGet(p.size.toLong())
        return p
    }

    /** Вызывает колбэк статистики (должно вызываться периодически извне). */
    fun reportStats() {
        onStats(TrafficStats(downBytes.get(), upBytes.get()))
    }

    // ----- перезапись адресов + чек-суммы -----

    /** Инкрементальная коррекция IPv4 header checksum + TCP/UDP checksum. */
    private fun fixChecksums(p: ByteArray, oldIp: Int, newIp: Int) {
        val ihl = (p[0].toInt() and 0x0F) * 4
        // IPv4 header checksum (offset 10)
        adjustChecksum(p, 10, oldIp, newIp)
        val proto = p[9].toInt() and 0xFF
        when (proto) {
            6 -> if (p.size >= ihl + 18) {                   // TCP: offset checksum
                adjustChecksum(p, ihl + 16, oldIp, newIp)
            }
            17 -> if (p.size >= ihl + 8) {                   // UDP: checksum (0 = none)
                val cs = ((p[ihl + 6].toInt() and 0xFF) shl 8) or (p[ihl + 7].toInt() and 0xFF)
                if (cs != 0) adjustChecksum(p, ihl + 6, oldIp, newIp)
            }
            else -> Unit // ICMP и прочее: псевдозаголовка не участвует
        }
    }

    /** RFC 1624: HC' = ~(~HC + ~m + m') для 16-битных слов. */
    private fun adjustChecksum(p: ByteArray, csOffset: Int, oldIp: Int, newIp: Int) {
        // старое/новое значение как два 16-битных слова (BE)
        val oldW1 = (oldIp ushr 16) and 0xFFFF
        val oldW2 = oldIp and 0xFFFF
        val newW1 = (newIp ushr 16) and 0xFFFF
        val newW2 = newIp and 0xFFFF
        var sum = ((p[csOffset].toInt() and 0xFF) shl 8) or (p[csOffset + 1].toInt() and 0xFF)
        sum = sum.inv() and 0xFFFF
        sum = (sum + (oldW1.inv() and 0xFFFF) + (oldW2.inv() and 0xFFFF)
                + newW1 + newW2) and 0xFFFF
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = sum.inv() and 0xFFFF
        p[csOffset] = ((cs ushr 8) and 0xFF).toByte()
        p[csOffset + 1] = (cs and 0xFF).toByte()
    }

    private fun isIpv4(p: ByteArray) = p.size >= 20 && (p[0].toInt() and 0xF0) == 0x40

    private fun readInt(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xFF) shl 24) or ((p[off + 1].toInt() and 0xFF) shl 16) or
                ((p[off + 2].toInt() and 0xFF) shl 8) or (p[off + 3].toInt() and 0xFF)

    private fun writeInt(p: ByteArray, off: Int, v: Int) {
        p[off] = ((v ushr 24) and 0xFF).toByte()
        p[off + 1] = ((v ushr 16) and 0xFF).toByte()
        p[off + 2] = ((v ushr 8) and 0xFF).toByte()
        p[off + 3] = (v and 0xFF).toByte()
    }

    private fun ipToInt(s: String): Int =
        s.split(".").fold(0) { acc, part -> (acc shl 8) or (part.toIntOrNull() ?: 0) }
}