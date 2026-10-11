package ru.gidravpn.hydra.servers

import ru.gidravpn.hydra.data.subscription.AwgParams
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

    companion object {
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
