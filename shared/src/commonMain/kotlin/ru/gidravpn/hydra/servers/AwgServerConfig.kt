package ru.gidravpn.hydra.servers

import ru.gidravpn.hydra.data.subscription.AwgParams
import org.json.JSONObject
import java.security.SecureRandom

/** Клиент своего сервера AmneziaWG: ключи и адрес в туннеле. */
data class AwgClient(
    val name: String,
    val privateKey: String,
    val publicKey: String,
    val presharedKey: String,
    val address: String,
)

/** Описание развёрнутого сервера — достаточно, чтобы пересоздать конфиги и добавлять/удалять клиентов. */
data class AwgServerSpec(
    val host: String,
    val port: Int,
    val generation: AwgGeneration,
    val params: Map<String, String>,
    val privateKey: String,
    val publicKey: String,
    val subnet: String = "10.8.1",     // сервер = .1, клиенты с .2
    val dns: String = "1.1.1.1",
    val mtu: Int = 1280,
    val iface: String = "awg0",
) {
    val serverAddress get() = "$subnet.1/24"

    /** Следующий свободный адрес клиента (.2–.254) или null, если все заняты. */
    fun nextAddress(used: Collection<String>): String? {
        val taken = used.map { it.substringBefore('/') }.toSet()
        return (2..254).map { "$subnet.$it" }.firstOrNull { it !in taken }?.let { "$it/32" }
    }

    private fun obfuscationLines(): List<String> =
        AwgParams.ALL.mapNotNull { p -> params[p.conf]?.let { "${p.confName} = $it" } }

    /** `/etc/amnezia/amneziawg/awg0.conf` на сервере. */
    fun serverConf(clients: List<AwgClient>, extraInterface: List<String> = emptyList()): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $serverAddress")
        appendLine("ListenPort = $port")
        extraInterface.forEach(::appendLine)
        obfuscationLines().forEach(::appendLine)
        clients.forEach {
            appendLine()
            appendLine("# client: ${it.name}")
            appendLine("[Peer]")
            appendLine("PublicKey = ${it.publicKey}")
            appendLine("PresharedKey = ${it.presharedKey}")
            appendLine("AllowedIPs = ${it.address}")
        }
    }

    /** `.conf` для клиента (Hydra, официальный AmneziaWG) — он же источник QR. */
    fun clientConf(c: AwgClient): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = ${c.privateKey}")
        appendLine("Address = ${c.address}")
        appendLine("DNS = $dns")
        appendLine("MTU = $mtu")
        obfuscationLines().forEach(::appendLine)
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $publicKey")
        appendLine("PresharedKey = ${c.presharedKey}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = ${if (host.contains(':')) "[$host]" else host}:$port")
        appendLine("PersistentKeepalive = 25")
    }

    fun toJson(): JSONObject = JSONObject().put("host", host).put("port", port).put("gen", generation.name)
        .put("params", JSONObject(params as Map<*, *>)).put("priv", privateKey).put("pub", publicKey)
        .put("subnet", subnet).put("dns", dns).put("mtu", mtu).put("iface", iface)

    companion object {
        fun fromJson(a: JSONObject): AwgServerSpec {
            val p = a.optJSONObject("params") ?: JSONObject()
            return AwgServerSpec(
                a.getString("host"), a.getInt("port"), runCatching { AwgGeneration.valueOf(a.getString("gen")) }.getOrDefault(AwgGeneration.V2_0),
                p.keys().asSequence().associateWith { k -> p.getString(k) },
                a.getString("priv"), a.getString("pub"), a.optString("subnet", "10.8.1"),
                a.optString("dns", "1.1.1.1"), a.optInt("mtu", 1280), a.optString("iface", "awg0"),
            )
        }

        /**
         * Восстановление по конфигу на сервере (`/etc/amnezia/amneziawg/awg0.conf`), когда копии `/etc/hydra/awg.json` нет —
         * сервер поставлен 0.8.0-rc1…rc3 с другого устройства. Закрытых ключей клиентов на сервере нет: такие клиенты приходят
         * с пустым privateKey (конфиг показать нельзя, удалить — можно). Открытый ключ сервера считается из закрытого.
         */
        fun fromServerConf(text: String, host: String, iface: String = "awg0"): Pair<AwgServerSpec, List<AwgClient>>? {
            var section = ""
            val iv = linkedMapOf<String, String>()
            val peers = mutableListOf<MutableMap<String, String>>()
            var pendingName: String? = null
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.startsWith("# client:")) { pendingName = line.substringAfter("# client:").trim(); continue }
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[")) {
                    section = line.lowercase()
                    if (section == "[peer]") peers += mutableMapOf<String, String>().also { m -> pendingName?.let { m["#name"] = it } }
                    pendingName = null
                    continue
                }
                val k = line.substringBefore('=').trim().lowercase()
                val v = line.substringAfter('=', "").trim()
                if (section == "[interface]") iv[k] = v else if (section == "[peer]") peers.lastOrNull()?.put(k, v)
            }
            val priv = iv["privatekey"] ?: return null
            val port = iv["listenport"]?.toIntOrNull() ?: return null
            val pub = runCatching { X25519.publicFromPrivateB64(priv) }.getOrNull() ?: return null
            val params = AwgParams.ALL.mapNotNull { p -> iv[p.conf]?.let { p.conf to it } }.toMap()
            val gen = when (AwgParams.version(params)) { "3.x" -> AwgGeneration.V3; "2.0" -> AwgGeneration.V2_0; "1.5" -> AwgGeneration.V1_5; else -> AwgGeneration.V1_0 }
            val subnet = iv["address"]?.substringBefore('/')?.substringBeforeLast('.') ?: "10.8.1"
            val spec = AwgServerSpec(host, port, gen, params, priv, pub, subnet, iface = iface)
            val clients = peers.mapIndexedNotNull { i, m ->
                val pk = m["publickey"] ?: return@mapIndexedNotNull null
                AwgClient(m["#name"] ?: "client${i + 1}", "", pk, m["presharedkey"].orEmpty(), m["allowedips"]?.substringBefore(',')?.trim() ?: "")
            }
            return spec to clients
        }

        fun create(host: String, port: Int, gen: AwgGeneration, rnd: SecureRandom = SecureRandom()): AwgServerSpec {
            val kp = X25519.generate(rnd)
            return AwgServerSpec(host, port, gen, AwgServerParams.generate(gen, rnd), kp.privateKey, kp.publicKey)
        }

        fun newClient(name: String, address: String, rnd: SecureRandom = SecureRandom()): AwgClient {
            val kp = X25519.generate(rnd)
            return AwgClient(name, kp.privateKey, kp.publicKey, X25519.randomKeyB64(rnd), address)
        }
    }
}
