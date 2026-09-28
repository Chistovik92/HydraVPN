package ru.gidravpn.hydra.data.subscription

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Лёгкий разбор URI вместо android.net.Uri (KMP). Повторяет поведение Android
 * там, где на него опирается LinkParser:
 *  - authority заканчивается на первом '/', '?' или '#' — '@' в пути, query или
 *    имени (#…) не считается разделителем userinfo;
 *  - IPv6-хост в скобках (`[2001:db8::1]:443`) отдаётся без скобок;
 *  - [userInfo] уже percent-декодирован (как Uri.getUserInfo), причём '+'
 *    остаётся '+' — в паролях Trojan/Hysteria2/SS-2022 он встречается постоянно.
 */
class UriParser(private val uri: String) {

    val scheme: String = uri.substringBefore("://", "").lowercase()
    private val rest = uri.substringAfter("://")

    /** Сырой фрагмент (имя профиля) — декодирует вызывающий. */
    val fragment: String = rest.substringAfter('#', "")
    private val beforeFragment = rest.substringBefore('#')
    val query: String = beforeFragment.substringAfter('?', "")
    private val beforeQuery = beforeFragment.substringBefore('?')
    private val authority = beforeQuery.substringBefore('/')
    val path: String = beforeQuery.substringAfter('/', "")

    val userInfo: String =
        if ('@' in authority) percentDecode(authority.substringBeforeLast('@')) else ""

    private val hostPort = authority.substringAfterLast('@')
    val host: String
    val port: Int

    init {
        if (hostPort.startsWith("[")) {
            host = hostPort.substringAfter('[').substringBefore(']')
            port = hostPort.substringAfter("]:", "").toIntOrNull() ?: -1
        } else {
            host = hostPort.substringBefore(':')
            port = hostPort.substringAfter(':', "").toIntOrNull() ?: -1
        }
    }

    fun queryMap(): Map<String, String> =
        query.split('&').filter { '=' in it }
            .associate { decode(it.substringBefore("=")) to decode(it.substringAfter("=")) }

    companion object {
        fun parse(uri: String): UriParser = UriParser(uri)
    }
}

/** Кодирование строки для URL. */
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Декодирование query/имени (form-encoding: '+' → пробел, как у Uri.getQueryParameter). */
fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

/** Percent-декодирование без form-правила '+' → пробел (userinfo, пароли). */
fun percentDecode(s: String): String =
    runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
