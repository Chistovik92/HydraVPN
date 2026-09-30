package ru.gidravpn.hydra.desktop.core

import ru.gidravpn.hydra.data.model.NetRuleType
import ru.gidravpn.hydra.data.model.NetworkRule
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Проверка и нормализация всего, что попадает в правила маршрутизации ядра: коды
 * стран, процессы, IP/домены. Значения приходят из UI, из hydra.json и из резервных
 * копий — ни одно не должно уронить ядро невалидным конфигом или сослаться на
 * произвольный файл (код страны превращается в путь к .srs).
 */
object Rules {
    const val MAX_RULES = 500
    const val MAX_APPS = 200

    private val COUNTRY = Regex("^[a-z]{2}$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val DOMAIN = Regex("^[a-z0-9_]([a-z0-9_-]{0,62}[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]{0,62}[a-z0-9_])?)*$")
    private val KEYWORD = Regex("^[a-z0-9._-]{1,64}$")

    fun countries(raw: List<String>): List<String> =
        raw.map { it.trim().lowercase() }.filter { COUNTRY.matches(it) }.distinct().take(300)

    /** Имя процесса или путь к программе: непустое, без управляющих символов, до 512 знаков. */
    fun app(raw: String): String? {
        val s = raw.trim().trim('"')
        if (s.isEmpty() || s.length > 512 || s.any { it.isISOControl() }) return null
        return s
    }

    fun apps(raw: List<String>): List<String> =
        raw.mapNotNull(::app).distinctBy { it.lowercase() }.take(MAX_APPS)

    /** Путь (есть разделитель) → process_path, иначе → process_name. */
    fun isPath(app: String) = '/' in app || '\\' in app

    /** null — значение не подходит для правила этого типа. */
    fun netRule(type: NetRuleType, raw: String): NetworkRule? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > 253) return null
        val value = when (type) {
            NetRuleType.IP_CIDR -> cidr(v)
            NetRuleType.DOMAIN -> domain(v)
            NetRuleType.DOMAIN_SUFFIX -> domain(v.removePrefix("*").removePrefix("."))
            NetRuleType.DOMAIN_KEYWORD -> v.lowercase().takeIf { KEYWORD.matches(it) }
        } ?: return null
        return NetworkRule(type, value)
    }

    /**
     * Тип по виду строки — для поля «Добавить» без выбора типа: адрес/подсеть → IP,
     * «*.site.ru» или «.site.ru» → домен с поддоменами, иначе домен с поддоменами тоже
     * (как ждёт пользователь, вписывая «youtube.com»).
     */
    fun guess(raw: String): NetworkRule? {
        val v = raw.trim()
        cidr(v)?.let { return NetworkRule(NetRuleType.IP_CIDR, it) }
        return netRule(NetRuleType.DOMAIN_SUFFIX, v)
    }

    /** «https://www.site.ru:443/path» → «www.site.ru». */
    private fun domain(raw: String): String? {
        val host = raw.lowercase()
            .substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':')
            .trimEnd('.')
        return host.takeIf { it.length in 1..253 && DOMAIN.matches(it) && !IPV4.matches(it) }
    }

    /** Адрес или подсеть: префикс по умолчанию /32 (IPv4) или /128 (IPv6); хост-биты не трогаем. */
    fun cidr(raw: String): String? {
        val addr = raw.substringBefore('/')
        val prefixText = raw.substringAfter('/', "")
        if (IPV4.matches(addr)) {
            if (IPV4.find(addr)!!.groupValues.drop(1).any { it.toInt() > 255 }) return null
            val prefix = if (prefixText.isEmpty()) 32 else prefixText.toIntOrNull()?.takeIf { it in 0..32 } ?: return null
            return "$addr/$prefix"
        }
        // IPv6: только литерал из hex-цифр и двоеточий — getByName тогда не делает DNS-запросов.
        if (':' !in addr || addr.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return null
        val ip = runCatching { InetAddress.getByName(addr) }.getOrNull() as? Inet6Address ?: return null
        val prefix = if (prefixText.isEmpty()) 128 else prefixText.toIntOrNull()?.takeIf { it in 0..128 } ?: return null
        return "${ip.hostAddress.substringBefore('%')}/$prefix"
    }
}
