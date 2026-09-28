package ru.gidravpn.hydra.data.subscription

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Lightweight URI parser to replace android.net.Uri for KMP.
 * Only implements the subset of functionality used in LinkParser and WireGuardParser.
 */
class UriParser(private val uri: String) {

    val scheme: String = uri.substringBefore("://").lowercase()
    val userInfo: String = extractUserInfo()
    val host: String = extractHost()
    val port: Int = extractPort()
    val path: String = extractPath()
    val query: String = extractQuery()
    val fragment: String = extractFragment()

    private fun extractUserInfo(): String {
        val afterScheme = uri.substringAfter("://")
        val beforeHost = afterScheme.substringBefore("@")
        return if ("@" in afterScheme && beforeHost.isNotEmpty()) beforeHost else ""
    }

    private fun extractHost(): String {
        var authority = uri.substringAfter("://")
        if ("@" in authority) {
            authority = authority.substringAfter("@")
        }
        // Remove path, query, fragment
        authority = authority.substringBefore("/")
        authority = authority.substringBefore("?")
        authority = authority.substringBefore("#")
        // Remove port
        return authority.substringBefore(":")
    }

    private fun extractPort(): Int {
        var authority = uri.substringAfter("://")
        if ("@" in authority) {
            authority = authority.substringAfter("@")
        }
        authority = authority.substringBefore("/")
        authority = authority.substringBefore("?")
        authority = authority.substringBefore("#")
        if (":" in authority) {
            return authority.substringAfter(":").toIntOrNull() ?: -1
        }
        return -1
    }

    private fun extractPath(): String {
        var afterAuthority = uri.substringAfter("://")
        if ("@" in afterAuthority) {
            afterAuthority = afterAuthority.substringAfter("@")
        }
        val path = afterAuthority.substringAfter("/")
        val pathOnly = path.substringBefore("?").substringBefore("#")
        return pathOnly
    }

    private fun extractQuery(): String {
        val afterQuery = uri.substringAfter("?", "")
        return afterQuery.substringBefore("#")
    }

    private fun extractFragment(): String {
        return uri.substringAfter("#", "")
    }

    fun queryMap(): Map<String, String> {
        return query.split('&').filter { '=' in it }.associate { it.substringBefore("=") to decode(it.substringAfter("=")) }
    }

    companion object {
        fun parse(uri: String): UriParser = UriParser(uri)
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/** Кодирование строки для URL. */
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** Декодирование строки из URL. */
fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)