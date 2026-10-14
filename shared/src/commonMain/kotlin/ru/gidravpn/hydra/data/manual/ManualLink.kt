package ru.gidravpn.hydra.data.manual

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/**
 * Ручной ввод сервера любого протокола (0.8.0-rc4), общий для Android и ПК.
 *
 * Форма строится по [fields] протокола, а результат — обычная ссылка (`vless://…`, `openflux://…`, `byedpi://…`) или `.conf`
 * (WireGuard, AmneziaWG), которую дальше разбирает тот же парсер, что и импорт. Так у ручного ввода нет своей логики
 * профилей, которая могла бы разойтись с импортом. До rc4 у OpenFlux, olcRTC и ByeDPI ручной формы не было вовсе,
 * у VLESS не было Reality и транспорта, а на ПК ручного ввода не было ни для одного протокола.
 */
object ManualLink {

    enum class Kind { TEXT, SECRET, NUMBER, CHOICE, MULTILINE }

    /**
     * Поле формы. [showIf] — поле видно, только если поле `first` имеет одно из значений `second`.
     * [required] — без него ссылку не собрать.
     */
    data class Field(
        val key: String,
        val ru: String,
        val en: String,
        val kind: Kind = Kind.TEXT,
        val required: Boolean = false,
        val default: String = "",
        val choices: List<String> = emptyList(),
        val hintRu: String = "",
        val hintEn: String = "",
        val showIf: Pair<String, Set<String>>? = null,
    ) {
        fun label(en: Boolean) = if (en) this.en else ru
        fun hint(en: Boolean) = if (en) hintEn else hintRu
        fun visible(v: Map<String, String>) = showIf?.let { (k, set) -> (v[k] ?: "") in set } ?: true
        /** Подпись варианта выбора: пустой — «—», no/yes — по-человечески. */
        fun choiceLabel(c: String, en: Boolean) = when (c) {
            "" -> "—"
            "no" -> if (en) "no" else "нет"
            "yes" -> if (en) "yes" else "да"
            else -> c
        }
    }

    private fun address(defPort: Int) = listOf(
        Field("address", "Адрес сервера", "Server address", required = true, hintRu = "IP или домен", hintEn = "IP or domain"),
        Field("port", "Порт", "Port", Kind.NUMBER, required = true, default = defPort.toString()),
    )

    private val TRANSPORT = Field("type", "Транспорт", "Transport", Kind.CHOICE, default = "tcp", choices = listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp", "h2"))
    private val PATH = Field("path", "Путь / serviceName", "Path / serviceName", showIf = "type" to setOf("ws", "grpc", "httpupgrade", "xhttp", "h2"))
    private val HOST = Field("host", "Host (заголовок)", "Host header", showIf = "type" to setOf("ws", "httpupgrade", "xhttp", "h2"))
    private val SNI = Field("sni", "SNI (имя сервера TLS)", "SNI (TLS server name)")
    private val FP = Field("fp", "Отпечаток TLS (uTLS)", "TLS fingerprint (uTLS)", Kind.CHOICE, default = "chrome", choices = listOf("chrome", "firefox", "safari", "ios", "edge", "random"))
    private val INSECURE = Field("insecure", "Не проверять сертификат", "Skip certificate check", Kind.CHOICE, default = "no", choices = listOf("no", "yes"))

    /** Протоколы, для которых есть ручная форма (WDTT — только ссылкой своего бота). */
    val SUPPORTED = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "tuic", "wireguard", "awg", "sstp", "l2tp", "pptp", "openflux", "olcrtc", "byedpi")

    fun fields(protocolId: String): List<Field> = when (protocolId) {
        "vless" -> address(443) + listOf(
            Field("uuid", "UUID", "UUID", required = true),
            Field("security", "Шифрование", "Security", Kind.CHOICE, default = "reality", choices = listOf("reality", "tls", "none")),
            SNI.copy(showIf = "security" to setOf("tls", "reality")),
            Field("pbk", "Открытый ключ Reality (pbk)", "Reality public key (pbk)", required = true, showIf = "security" to setOf("reality")),
            Field("sid", "Short ID Reality (sid)", "Reality short ID (sid)", showIf = "security" to setOf("reality")),
            Field("flow", "Flow", "Flow", Kind.CHOICE, default = "xtls-rprx-vision", choices = listOf("xtls-rprx-vision", "")),
            FP.copy(showIf = "security" to setOf("tls", "reality")),
            TRANSPORT, PATH, HOST,
            INSECURE.copy(showIf = "security" to setOf("tls")),
        )
        "vmess" -> address(443) + listOf(
            Field("uuid", "UUID", "UUID", required = true),
            Field("aid", "alterId", "alterId", Kind.NUMBER, default = "0"),
            Field("security", "Шифрование", "Security", Kind.CHOICE, default = "tls", choices = listOf("tls", "none")),
            SNI.copy(showIf = "security" to setOf("tls")),
            TRANSPORT.copy(default = "ws"), PATH, HOST,
        )
        "trojan" -> address(443) + listOf(
            Field("password", "Пароль", "Password", Kind.SECRET, required = true),
            SNI, FP, TRANSPORT, PATH, HOST, INSECURE,
        )
        "ss" -> address(8388) + listOf(
            Field("method", "Шифр", "Cipher", Kind.CHOICE, default = "aes-256-gcm",
                choices = listOf("aes-256-gcm", "aes-128-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305")),
            Field("password", "Пароль", "Password", Kind.SECRET, required = true),
        )
        "hysteria2" -> address(443) + listOf(
            Field("password", "Пароль", "Password", Kind.SECRET, required = true),
            SNI,
            Field("obfs-password", "Пароль обфускации salamander (если задан)", "Salamander obfuscation password (if set)", Kind.SECRET),
            INSECURE,
        )
        "tuic" -> address(443) + listOf(
            Field("uuid", "UUID", "UUID", required = true),
            Field("password", "Пароль", "Password", Kind.SECRET, required = true),
            SNI,
            Field("congestion_control", "Управление перегрузкой", "Congestion control", Kind.CHOICE, default = "bbr", choices = listOf("bbr", "cubic", "new_reno")),
            INSECURE,
        )
        "sstp", "l2tp", "pptp" -> address(when (protocolId) { "sstp" -> 443; "l2tp" -> 1701; else -> 1723 }) + listOf(
            Field("user", "Пользователь", "User", required = true),
            Field("password", "Пароль", "Password", Kind.SECRET),
        ) + (if (protocolId == "l2tp") listOf(Field("tunnel_secret", "Секрет туннеля L2TP (если задан)", "L2TP tunnel secret (if set)", Kind.SECRET)) else emptyList())
        "wireguard", "awg" -> listOf(
            Field("conf", "Конфиг целиком (.conf) — или заполните поля ниже", "Whole .conf — or fill in the fields below", Kind.MULTILINE),
            Field("privatekey", "Закрытый ключ (PrivateKey)", "PrivateKey", Kind.SECRET, showIf = "conf" to setOf("")),
            Field("ifaddress", "Адрес в туннеле (Address)", "Tunnel address (Address)", default = "10.8.1.2/32", showIf = "conf" to setOf("")),
            Field("dns", "DNS", "DNS", default = "1.1.1.1", showIf = "conf" to setOf("")),
            Field("publickey", "Открытый ключ сервера (PublicKey)", "Server PublicKey", showIf = "conf" to setOf("")),
            Field("presharedkey", "PresharedKey (если есть)", "PresharedKey (if any)", Kind.SECRET, showIf = "conf" to setOf("")),
            Field("endpoint", "Сервер:порт (Endpoint)", "Endpoint host:port", showIf = "conf" to setOf("")),
        ) + (if (protocolId == "awg") listOf(
            Field("awgparams", "Параметры AmneziaWG (Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5 — по строке «ключ = значение»)",
                "AmneziaWG parameters (Jc, Jmin, Jmax, S1–S4, H1–H4, I1–I5 — one «key = value» per line)", Kind.MULTILINE, showIf = "conf" to setOf("")),
        ) else emptyList())
        "openflux" -> listOf(
            Field("transport", "Транспорт", "Transport", Kind.CHOICE, default = "direct", choices = listOf("direct", "vyandex", "yandex", "mailru", "cupsonline", "oneme")),
            Field("url", "Адрес узла host:порт / ссылка на документ", "Node host:port / document link", required = true, showIf = "transport" to setOf("direct", "vyandex", "yandex", "mailru", "cupsonline"),
                hintRu = "direct — 203.0.113.5:24680; Яндекс — https://docs.yandex.ru/edit/d/…", hintEn = "direct — 203.0.113.5:24680; Yandex — https://docs.yandex.ru/edit/d/…"),
            Field("maxToken", "Токен MAX", "MAX token", Kind.SECRET, required = true, showIf = "transport" to setOf("oneme")),
            Field("maxUid", "ID пользователя MAX", "MAX user ID", required = true, showIf = "transport" to setOf("oneme")),
            Field("key", "Ключ шифрования (64 hex или пароль)", "Encryption key (64 hex or password)", Kind.SECRET),
        )
        "olcrtc" -> listOf(
            Field("provider", "Сервис комнаты", "Room provider", Kind.CHOICE, default = "telemost", choices = listOf("telemost", "jazz", "wbstream")),
            Field("transport", "Транспорт", "Transport", Kind.CHOICE, default = "datachannel", choices = listOf("datachannel", "videochannel")),
            Field("room", "ID комнаты", "Room ID", required = true),
            Field("key", "Ключ шифрования (64 hex)", "Encryption key (64 hex)", Kind.SECRET, required = true),
        )
        "byedpi" -> listOf(
            Field("strategy", "Стратегия (аргументы ciadpi; пусто — по умолчанию)", "Strategy (ciadpi arguments; empty = default)", Kind.MULTILINE,
                hintRu = "Например: -o1 -At,r,s -d1 — подобрать можно в «Маршрутизация → Обход DPI»", hintEn = "E.g. -o1 -At,r,s -d1 — tune it in Routing → DPI bypass"),
        )
        else -> emptyList()
    }

    /** Значения по умолчанию для формы. */
    fun defaults(protocolId: String): Map<String, String> = fields(protocolId).associate { it.key to it.default }

    /** Чего не хватает (подписи полей), пусто — можно собирать. */
    fun missing(protocolId: String, v: Map<String, String>, en: Boolean = false): List<String> {
        val fs = fields(protocolId)
        if ((protocolId == "wireguard" || protocolId == "awg") && v["conf"].orEmpty().isNotBlank()) return emptyList()
        val need = fs.filter { it.visible(v) && (it.required || ((protocolId == "wireguard" || protocolId == "awg") && it.key in WG_REQUIRED)) }
        return need.filter { v[it.key].isNullOrBlank() }.map { it.label(en) }
    }

    private val WG_REQUIRED = setOf("privatekey", "ifaddress", "publickey", "endpoint")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun hostPart(h: String) = h.trim().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }

    /**
     * Ссылка или `.conf` из полей; null — не хватает обязательного ([missing]).
     * Имя — в «#…» ссылки (или `$…` у olcRTC); у `.conf` имя задаёт вызывающий при импорте.
     */
    fun build(protocolId: String, name: String, values: Map<String, String>): String? {
        val v = defaults(protocolId) + values.mapValues { it.value.trim() }.filterValues { true }
        if (missing(protocolId, v).isNotEmpty()) return null
        val n = name.trim()
        val frag = if (n.isEmpty()) "" else "#" + enc(n)
        fun q(vararg pairs: Pair<String, String?>) = pairs.filter { !it.second.isNullOrBlank() }.joinToString("&") { "${it.first}=${enc(it.second!!)}" }
        val addr = "${hostPart(v["address"].orEmpty())}:${v["port"].orEmpty()}"
        val ins = if (v["insecure"] == "yes") "1" else null
        val tr = v["type"].orEmpty().ifBlank { "tcp" }
        val path = v["path"].takeIf { tr != "tcp" }
        val pathKey = if (tr == "grpc") "serviceName" else "path"
        return when (protocolId) {
            "vless" -> {
                val sec = v["security"].orEmpty()
                "vless://${enc(v["uuid"]!!)}@$addr?" + q("type" to tr, "security" to sec, "sni" to v["sni"].takeIf { sec != "none" },
                    "fp" to v["fp"].takeIf { sec != "none" }, "pbk" to v["pbk"].takeIf { sec == "reality" }, "sid" to v["sid"].takeIf { sec == "reality" },
                    "flow" to v["flow"].takeIf { tr == "tcp" && sec != "none" }, pathKey to path, "host" to v["host"].takeIf { tr != "tcp" && tr != "grpc" },
                    "allowInsecure" to ins.takeIf { sec == "tls" }) + frag
            }
            "vmess" -> {
                val j = JSONObject().put("v", "2").put("ps", n.ifEmpty { "VMess ${v["address"]}" }).put("add", v["address"]).put("port", v["port"])
                    .put("id", v["uuid"]).put("aid", v["aid"].orEmpty().ifBlank { "0" }).put("net", tr).put("type", "none")
                    .put("host", v["host"].orEmpty()).put("path", path.orEmpty()).put("tls", if (v["security"] == "tls") "tls" else "").put("sni", v["sni"].orEmpty())
                "vmess://" + Base64.getEncoder().encodeToString(j.toString().toByteArray())
            }
            "trojan" -> "trojan://${enc(v["password"]!!)}@$addr?" + q("security" to "tls", "type" to tr, "sni" to v["sni"], "fp" to v["fp"],
                pathKey to path, "host" to v["host"].takeIf { tr != "tcp" && tr != "grpc" }, "allowInsecure" to ins) + frag
            "ss" -> "ss://" + Base64.getUrlEncoder().withoutPadding().encodeToString("${v["method"]}:${v["password"]}".toByteArray()) + "@$addr" + frag
            "hysteria2" -> "hysteria2://${enc(v["password"]!!)}@$addr?" + q("sni" to v["sni"],
                "obfs" to (if (v["obfs-password"].isNullOrBlank()) null else "salamander"), "obfs-password" to v["obfs-password"], "insecure" to ins) + frag
            "tuic" -> "tuic://${enc(v["uuid"]!!)}:${enc(v["password"]!!)}@$addr?" + q("sni" to v["sni"], "congestion_control" to v["congestion_control"],
                "alpn" to "h3", "allow_insecure" to ins) + frag
            "sstp", "l2tp", "pptp" -> "$protocolId://${enc(v["user"]!!)}:${enc(v["password"].orEmpty())}@$addr" +
                (q("tunnel_secret" to v["tunnel_secret"]).let { if (it.isEmpty()) "" else "?$it" }) + frag
            "wireguard", "awg" -> v["conf"].orEmpty().takeIf { it.isNotBlank() } ?: buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = ${v["privatekey"]}")
                appendLine("Address = ${v["ifaddress"]}")
                v["dns"]?.takeIf { it.isNotBlank() }?.let { appendLine("DNS = $it") }
                if (protocolId == "awg") v["awgparams"].orEmpty().lines().map { it.trim() }.filter { '=' in it }.forEach { appendLine(it) }
                appendLine()
                appendLine("[Peer]")
                appendLine("PublicKey = ${v["publickey"]}")
                v["presharedkey"]?.takeIf { it.isNotBlank() }?.let { appendLine("PresharedKey = $it") }
                appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
                appendLine("Endpoint = ${v["endpoint"]}")
                appendLine("PersistentKeepalive = 25")
            }
            "openflux" -> {
                val t = v["transport"].orEmpty()
                "openflux://$t?" + q("url" to v["url"].takeIf { t != "oneme" }, "maxToken" to v["maxToken"].takeIf { t == "oneme" },
                    "maxUid" to v["maxUid"].takeIf { t == "oneme" }, "key" to v["key"]) + frag
            }
            "olcrtc" -> "olcrtc://${v["provider"]}?${v["transport"]}@${v["room"]}#${v["key"]}" + if (n.isEmpty()) "" else "\$$n"
            "byedpi" -> "byedpi://?s=${enc(v["strategy"].orEmpty().replace('\n', ' ').trim())}" + (if (n.isEmpty()) "#" + enc("Обход DPI") else frag)
            else -> null
        }
    }
}
