package ru.gidravpn.hydra.data.geo

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.MessageDigest

/** Скачивание: интерфейс, чтобы подменять в тестах и использовать прокси (локальный mixed-inbound включённого туннеля). */
interface GeoHttp {
    /** Тело ответа; бросает исключение при любой ошибке или превышении [maxBytes]. */
    fun get(url: String, maxBytes: Long): ByteArray

    /** Скачать в файл [dest] (большие `.dat` не держим в памяти: у телефона на 70 МБ массива кучи не хватит). */
    fun getFile(url: String, maxBytes: Long, dest: File) { dest.writeBytes(get(url, maxBytes)) }
}

class JvmGeoHttp(private val proxyPort: Int = 0, private val timeoutMs: Int = 20_000) : GeoHttp {
    private fun <T> request(url: String, maxBytes: Long, sink: (java.io.InputStream) -> T): T {
        val u = URL(url)
        require(u.protocol == "https") { "только https" }
        val c = (if (proxyPort > 0) u.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))) else u.openConnection()) as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "HydraVPN-geo")
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            if (c.contentLengthLong > maxBytes) error("файл больше ${maxBytes / (1 shl 20)} МБ")
            return c.inputStream.use(sink)
        } finally { c.disconnect() }
    }

    /** Копирует поток с потолком [maxBytes] (заголовок Content-Length можно не прислать или солгать). */
    private fun copyLimited(ins: java.io.InputStream, out: java.io.OutputStream, maxBytes: Long) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) error("файл больше ${maxBytes / (1 shl 20)} МБ")
            out.write(buf, 0, n)
        }
    }

    override fun get(url: String, maxBytes: Long): ByteArray = request(url, maxBytes) { ins ->
        java.io.ByteArrayOutputStream().also { copyLimited(ins, it, maxBytes) }.toByteArray()
    }

    override fun getFile(url: String, maxBytes: Long, dest: File) {
        try {
            request(url, maxBytes) { ins -> dest.outputStream().use { copyLimited(ins, it, maxBytes) } }
        } catch (e: Exception) { dest.delete(); throw e }
    }
}

/**
 * Хранилище скачанных баз: `<dir>/geoip|geosite/<имя>.srs` (бинарный rule-set) или `.json` (rule-set «source»),
 * рядом `.prev` — предыдущая версия для отката, `meta.json` — откуда, когда, хеш. Вшитые базы сюда не входят:
 * [resolve] возвращает null, и вызывающий берёт вшитую.
 */
class GeoStore(private val dir: File) {

    /** Хранилище открывают из нескольких мест (WorkManager, экран, подключение): блокировка одна на каталог, а не на экземпляр. */
    private val lock: Any = locks.getOrPut(runCatching { dir.canonicalPath }.getOrDefault(dir.path)) { Any() }

    data class Entry(
        val kind: GeoKind, val name: String, val file: File, val sha256: String, val size: Long,
        val updatedAt: Long, val source: String, val hasPrev: Boolean,
    )

    enum class Outcome { INSTALLED, UNCHANGED, REJECTED }
    data class Installed(val outcome: Outcome, val reason: String = "")

    private val metaFile get() = File(dir, "meta.json")

    /** Запись через временный файл: обрыв посреди записи не обнуляет список скачанных баз. */
    private fun writeMeta(m: JSONObject) {
        val tmp = File(metaFile.path + ".tmp")
        tmp.writeText(m.toString())
        try {
            java.nio.file.Files.move(tmp.toPath(), metaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp.toPath(), metaFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun meta(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())
    private fun key(kind: GeoKind, name: String) = "${kind.dir}/$name"
    private fun fileFor(kind: GeoKind, name: String, source: Boolean) =
        File(dir, "${kind.dir}/${safe(name)}.${if (source) "json" else "srs"}")

    /** Путь к скачанной базе; null — нет или повреждена (тогда используется вшитая). */
    fun resolve(kind: GeoKind, name: String): File? = synchronized(lock) {
        val e = meta().optJSONObject(key(kind, name)) ?: return null
        val f = fileFor(kind, name, e.optString("format") == "source")
        return f.takeIf { it.isFile && it.length() == e.optLong("size", -1) }
    }

    fun entries(): List<Entry> = synchronized(lock) {
        val m = meta()
        m.keys().asSequence().mapNotNull { k ->
            val e = m.getJSONObject(k)
            val kind = GeoKind.fromDir(k.substringBefore('/')) ?: return@mapNotNull null
            val name = k.substringAfter('/')
            val f = fileFor(kind, name, e.optString("format") == "source")
            if (!f.isFile) return@mapNotNull null
            Entry(kind, name, f, e.optString("sha256"), e.optLong("size"), e.optLong("updated"), e.optString("source"),
                File(f.path + ".prev").isFile)
        }.sortedWith(compareBy({ it.kind }, { it.name })).toList()
    }

    /** Проверка и атомарная установка. [source]=true — rule-set в формате JSON. */
    fun install(kind: GeoKind, name: String, bytes: ByteArray, source: Boolean, from: String, now: Long = System.currentTimeMillis()): Installed = synchronized(lock) {
        validate(bytes, source)?.let { return Installed(Outcome.REJECTED, it) }
        val sha = sha256(bytes)
        val m = meta()
        val k = key(kind, name)
        val old = m.optJSONObject(k)
        val target = fileFor(kind, name, source)
        val oldFile = old?.let { fileFor(kind, name, it.optString("format") == "source") }
        if (old != null && old.optString("sha256") == sha && oldFile?.isFile == true) {
            m.put(k, old.put("checked", now)); writeMeta(m)
            return Installed(Outcome.UNCHANGED)
        }
        // Защита от подмены/обрезки: новая база не должна быть втрое меньше прежней того же формата.
        if (oldFile != null && oldFile.isFile && old?.optString("format") == (if (source) "source" else "srs") &&
            bytes.size < oldFile.length() * 0.4) return Installed(Outcome.REJECTED, "новая база подозрительно мала (${bytes.size} Б против ${oldFile.length()} Б)")

        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (oldFile != null && oldFile.isFile) {
            val prev = File(target.path + ".prev")
            runCatching { prev.delete() }
            if (oldFile.path == target.path) oldFile.copyTo(prev, overwrite = true)
            else { oldFile.renameTo(File(oldFile.path + ".prev")) }
        }
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "не удалось заменить ${target.name}" } }
        m.put(k, JSONObject().put("sha256", sha).put("size", bytes.size.toLong()).put("updated", now).put("checked", now)
            .put("source", from).put("format", if (source) "source" else "srs"))
        writeMeta(m)
        return Installed(Outcome.INSTALLED)
    }

    /** Вернуть предыдущую версию базы. */
    fun rollback(kind: GeoKind, name: String): Boolean = synchronized(lock) {
        val m = meta()
        val k = key(kind, name)
        val e = m.optJSONObject(k) ?: return false
        val cur = fileFor(kind, name, e.optString("format") == "source")
        val prev = listOf(File(cur.path + ".prev"), File(fileFor(kind, name, !(e.optString("format") == "source")).path + ".prev")).firstOrNull { it.isFile } ?: return false
        val prevIsSource = prev.name.removeSuffix(".prev").endsWith(".json")
        val bytes = prev.readBytes()
        if (validate(bytes, prevIsSource) != null) return false
        val restored = fileFor(kind, name, prevIsSource)
        cur.delete()
        restored.writeBytes(bytes)
        prev.delete()
        m.put(k, JSONObject().put("sha256", sha256(bytes)).put("size", bytes.size.toLong()).put("updated", System.currentTimeMillis())
            .put("checked", System.currentTimeMillis()).put("source", "rollback").put("format", if (prevIsSource) "source" else "srs"))
        writeMeta(m)
        return true
    }

    fun remove(kind: GeoKind, name: String) = synchronized(lock) {
        val m = meta()
        val e = m.optJSONObject(key(kind, name)) ?: return
        val f = fileFor(kind, name, e.optString("format") == "source")
        listOf(f, File(f.path + ".prev")).forEach { it.delete() }
        m.remove(key(kind, name)); writeMeta(m)
    }

    /** Когда база последний раз сверялась с источником (для расписания); 0 — никогда. */
    fun lastChecked(): Long = synchronized(lock) {
        val m = meta()
        m.keys().asSequence().maxOfOrNull { m.getJSONObject(it).optLong("checked") } ?: 0L
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        const val MAX_BYTES = 40L * 1024 * 1024

        private fun safe(name: String) = name.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == '!' }.ifEmpty { "x" }.replace("..", "_")

        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** null — файл годится. */
        fun validate(bytes: ByteArray, source: Boolean): String? {
            if (bytes.size < 8) return "файл слишком короткий"
            if (bytes.size > MAX_BYTES) return "файл слишком большой"
            return if (source) {
                runCatching {
                    val o = JSONObject(String(bytes, Charsets.UTF_8))
                    val rules = o.getJSONArray("rules")
                    if (rules.length() == 0) "в списке нет правил" else null
                }.getOrElse { "не rule-set JSON" }
            } else {
                // «SRS» + версия формата; libbox 1.12 читает версии до 3.
                if (bytes[0] != 'S'.code.toByte() || bytes[1] != 'R'.code.toByte() || bytes[2] != 'S'.code.toByte()) "это не rule-set sing-box (.srs)"
                else if (bytes[3] !in 1..3) "версия rule-set ${bytes[3]} не поддерживается ядром" else null
            }
        }

        /** Текстовый список → rule-set «source»: строки с `/` или IP — CIDR, остальное — домены (домен + поддомены). */
        fun listToSource(text: String, kind: GeoKind): ByteArray {
            val items = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
            val rule = JSONObject()
            if (kind == GeoKind.IP) {
                rule.put("ip_cidr", JSONArray(items.map { if ('/' in it) it else if (':' in it) "$it/128" else "$it/32" }))
            } else {
                val suffix = items.filter { !it.startsWith("full:") && !it.startsWith("keyword:") }.map { it.removePrefix("domain:").removePrefix(".") }
                rule.put("domain_suffix", JSONArray(suffix))
                items.filter { it.startsWith("full:") }.map { it.removePrefix("full:") }.takeIf { it.isNotEmpty() }?.let { rule.put("domain", JSONArray(it)) }
                items.filter { it.startsWith("keyword:") }.map { it.removePrefix("keyword:") }.takeIf { it.isNotEmpty() }?.let { rule.put("domain_keyword", JSONArray(it)) }
            }
            return JSONObject().put("version", 2).put("rules", JSONArray().put(rule)).toString().toByteArray()
        }
    }
}

/** Обновление баз по списку нужных имён. */
class GeoUpdater(private val store: GeoStore, private val http: GeoHttp) {

    data class Item(val kind: GeoKind, val name: String, val outcome: GeoStore.Outcome?, val message: String)
    data class Report(val items: List<Item>) {
        val installed get() = items.count { it.outcome == GeoStore.Outcome.INSTALLED }
        val unchanged get() = items.count { it.outcome == GeoStore.Outcome.UNCHANGED }
        val failed get() = items.count { it.outcome == null || it.outcome == GeoStore.Outcome.REJECTED }
    }

    fun update(settings: GeoSettings, wanted: Collection<Pair<GeoKind, String>>, log: (String) -> Unit = {}): Report {
        val items = mutableListOf<Item>()
        val datCache = mutableMapOf<String, File?>()
        try {
        for ((kind, rawName) in wanted.distinct()) {
            val name = rawName.trim().lowercase()
            if (name.isEmpty()) continue
            val item = runCatching { updateOne(settings, kind, name, datCache, log) }.getOrElse {
                Item(kind, name, null, it.message ?: it.javaClass.simpleName)
            }
            log("Geo-базы: ${kind.dir}/$name — ${item.outcome?.name?.lowercase() ?: "ошибка"}${if (item.message.isNotEmpty()) " (${item.message})" else ""}")
            items += item
        }
        } finally { datCache.values.forEach { runCatching { it?.delete() } } }
        return Report(items)
    }

    private fun datFile(url: String, cache: MutableMap<String, File?>): File = cache.getOrPut(url) {
        File.createTempFile("geo-dat", ".tmp").also { http.getFile(url, DAT_MAX, it) }
    }!!

    private fun updateOne(s: GeoSettings, kind: GeoKind, name: String, datCache: MutableMap<String, File?>, log: (String) -> Unit): Item {
        val custom = s.custom.firstOrNull { it.kind == kind && it.name.equals(name, true) }
        if (custom != null) return fromCustom(custom, kind, name, datCache)
        val src = GeoSources.byId(if (kind == GeoKind.IP) s.ipSource else s.siteSource)
        if (src.format == GeoFormat.DAT) {
            val url = src.templates(kind).first()
            return fromDat(datFile(url, datCache), kind, name, src.id)
        }
        var lastError = "нет ответа"
        // Доменные наборы стран в апстримах зовутся `category-<код>` (`ru` → `category-ru`), `cn` — как есть.
        val remoteNames = if (kind == GeoKind.SITE && name.length == 2) listOf(name, "category-$name") else listOf(name)
        for (remote in remoteNames) for (t in src.templates(kind)) {
            val url = t.replace("{name}", remote)
            try {
                val r = store.install(kind, name, http.get(url, GeoStore.MAX_BYTES), source = false, from = src.id)
                return Item(kind, name, r.outcome, r.reason)
            } catch (e: Exception) { lastError = "${e.message}"; log("Geo-базы: $url — $lastError") }
        }
        return Item(kind, name, null, lastError)
    }

    private fun fromCustom(c: CustomGeoSource, kind: GeoKind, name: String, datCache: MutableMap<String, File?>): Item {
        if (c.type == CustomGeoSource.TYPE_DAT) return fromDat(datFile(c.url, datCache), kind, name, "custom")
        val bytes = http.get(c.url, GeoStore.MAX_BYTES)
        return when (c.type) {
            CustomGeoSource.TYPE_SRS -> store.install(kind, name, bytes, false, "custom").let { Item(kind, name, it.outcome, it.reason) }
            else -> store.install(kind, name, GeoStore.listToSource(String(bytes, Charsets.UTF_8), kind), true, "custom").let { Item(kind, name, it.outcome, it.reason) }
        }
    }

    private fun fromDat(dat: File, kind: GeoKind, name: String, from: String): Item {
        fun find(n: String) = dat.inputStream().use { DatConverter.extract(it, kind, n) }
        val json = (if (kind == GeoKind.SITE && name.length == 2) find("category-$name") else null)
            ?: find(name)
            ?: return Item(kind, name, null, "в .dat нет записи «$name»")
        return store.install(kind, name, json, true, from).let { Item(kind, name, it.outcome, it.reason) }
    }

    companion object { const val DAT_MAX = 120L * 1024 * 1024 }
}

/**
 * Достаёт одну запись из `geoip.dat` / `geosite.dat` (protobuf v2fly) и пишет её как rule-set «source» sing-box.
 * Читает поток, не разбирая остальное, так что 70 МБ файла не превращаются в 70 МБ объектов.
 */
object DatConverter {

    private fun readVarint(s: InputStream): Long {
        var shift = 0; var r = 0L
        while (true) {
            val b = s.read()
            if (b < 0) return -1
            r = r or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return r
            shift += 7
        }
    }

    /** Запись .dat длиннее этого — файл повреждён или враждебен (самый крупный набор geosite — единицы мегабайт). */
    private const val MAX_ENTRY = 64 * 1024 * 1024

    /** InputStream.skip может пропустить меньше запрошенного (BufferedInputStream на границе буфера) - тогда разбор уезжает. */
    private fun skipFully(s: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = s.skip(left)
            if (k > 0) left -= k else { if (s.read() < 0) error("обрыв файла .dat"); left-- }
        }
    }

    private fun readBytes(s: InputStream, n: Int): ByteArray {
        require(n in 0..MAX_ENTRY) { "повреждённый .dat: запись длиной $n" }
        val a = ByteArray(n); var o = 0
        while (o < n) { val r = s.read(a, o, n - o); if (r < 0) error("обрыв файла .dat"); o += r }
        return a
    }

    /** Поля сообщения: номер → список (длина-разделённые байты | число). */
    private fun fields(b: ByteArray): List<Pair<Int, Any>> {
        val s = ByteArrayInputStream(b)
        val out = mutableListOf<Pair<Int, Any>>()
        while (s.available() > 0) {
            val tag = readVarint(s)
            if (tag < 0) break
            val num = (tag shr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> out += num to readVarint(s)
                2 -> out += num to readBytes(s, readVarint(s).toInt())
                1 -> s.skip(8)
                5 -> s.skip(4)
                else -> error("неизвестный тип поля protobuf")
            }
        }
        return out
    }

    /** null — записи с таким именем нет. */
    fun extract(input: InputStream, kind: GeoKind, name: String): ByteArray? {
        val s = BufferedInputStream(input, 1 shl 16)
        val want = name.lowercase()
        while (true) {
            val tag = readVarint(s)
            if (tag < 0) return null
            val type = (tag and 7).toInt()
            if (type != 2) { if (type == 0) readVarint(s) else error("формат .dat не распознан"); continue }
            val len = readVarint(s)
            require(len in 0..MAX_ENTRY) { "повреждённый .dat: запись длиной $len" }
            if (tag shr 3 != 1L) { skipFully(s, len); continue }
            val entry = readBytes(s, len.toInt())
            val f = fields(entry)
            val code = (f.firstOrNull { it.first == 1 }?.second as? ByteArray)?.toString(Charsets.UTF_8)?.lowercase()
            if (code != want) continue
            return if (kind == GeoKind.IP) ipSource(f) else siteSource(f)
        }
    }

    private fun ipSource(f: List<Pair<Int, Any>>): ByteArray {
        val cidr = f.filter { it.first == 2 }.mapNotNull { (_, v) ->
            val c = fields(v as ByteArray)
            val ip = c.firstOrNull { it.first == 1 }?.second as? ByteArray ?: return@mapNotNull null
            val prefix = (c.firstOrNull { it.first == 2 }?.second as? Long ?: 0L).toInt()
            val addr = java.net.InetAddress.getByAddress(ip).hostAddress
            "$addr/$prefix"
        }
        return JSONObject().put("version", 2).put("rules", JSONArray().put(JSONObject().put("ip_cidr", JSONArray(cidr)))).toString().toByteArray()
    }

    private fun siteSource(f: List<Pair<Int, Any>>): ByteArray {
        val kw = mutableListOf<String>(); val rx = mutableListOf<String>(); val suffix = mutableListOf<String>(); val full = mutableListOf<String>()
        f.filter { it.first == 2 }.forEach { (_, v) ->
            val d = fields(v as ByteArray)
            val type = (d.firstOrNull { it.first == 1 }?.second as? Long ?: 0L).toInt()
            val value = (d.firstOrNull { it.first == 2 }?.second as? ByteArray)?.toString(Charsets.UTF_8) ?: return@forEach
            when (type) { 0 -> kw += value; 1 -> rx += value; 2 -> suffix += value; 3 -> full += value }
        }
        val rule = JSONObject()
        if (suffix.isNotEmpty()) rule.put("domain_suffix", JSONArray(suffix))
        if (full.isNotEmpty()) rule.put("domain", JSONArray(full))
        if (kw.isNotEmpty()) rule.put("domain_keyword", JSONArray(kw))
        if (rx.isNotEmpty()) rule.put("domain_regex", JSONArray(rx))
        return JSONObject().put("version", 2).put("rules", JSONArray().put(rule)).toString().toByteArray()
    }
}
