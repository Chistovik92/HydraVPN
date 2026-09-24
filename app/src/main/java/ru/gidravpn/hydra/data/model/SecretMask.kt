package ru.gidravpn.hydra.data.model

/**
 * «Скрывать ключи» (Фаза 8): ссылка сервера/подписки для показа на экране — без UUID,
 * паролей, ключей Reality и токенов подписки. Копирование, QR и «Поделиться» по-прежнему
 * отдают полную ссылку: маска защищает от чужого взгляда и скриншота, а не от владельца.
 */
object SecretMask {
    const val DOTS = "••••"

    /** Параметры запроса, в которых лежат секреты (Reality, obfs, WireGuard и т. п.). */
    private val secretParams = setOf(
        "pbk", "sid", "password", "pass", "psk", "key", "privatekey", "private_key", "publickey",
        "public_key", "presharedkey", "obfs-password", "obfs_password", "auth", "token", "uuid", "id", "spx",
    )

    fun mask(link: String): String {
        val schemeEnd = link.indexOf("://")
        if (schemeEnd < 0) return link
        val scheme = link.substring(0, schemeEnd + 3)
        var rest = link.substring(schemeEnd + 3)

        // vmess:// и прочие «всё в base64» — секреты внутри, показываем только схему.
        if ('@' !in rest && '/' !in rest && '?' !in rest && rest.length > 24) return scheme + DOTS

        // user:pass@host → ••••@host
        val at = rest.indexOf('@')
        val pathStart = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (at >= 0 && (pathStart < 0 || at < pathStart)) rest = DOTS + rest.substring(at)

        // http(s)-подписки: токен обычно — последний сегмент пути (/sub/<token>).
        if (scheme.startsWith("http")) {
            rest = rest.replace(Regex("(/[^/?#]+/)([^/?#]{8,})(?=[?#]|$)")) { m -> m.groupValues[1] + DOTS }
        }

        // ?pbk=…&sid=… → ?pbk=••••&sid=••••
        rest = rest.replace(Regex("([?&])([^=&#]+)=([^&#]*)")) { m ->
            val name = m.groupValues[2]
            if (name.lowercase() in secretParams && m.groupValues[3].isNotEmpty()) "${m.groupValues[1]}$name=$DOTS"
            else m.value
        }
        return scheme + rest
    }
}
