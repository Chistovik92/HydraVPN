package ru.gidravpn.hydra.vpn.core

import ru.gidravpn.hydra.data.model.Protocol
import ru.gidravpn.hydra.data.model.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/** Строит конфиг Xray-core (JSON) с локальным socks-inbound и proxy-outbound. */
object XrayConfigBuilder {
    fun build(p: ServerProfile, socksPort: Int = 10808): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))

        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", socksPort)
            put("protocol", "socks")
            put("settings", JSONObject().put("udp", true))
        }))

        val outbound = JSONObject().put("tag", "proxy").put("protocol", p.protocol?.id ?: "vless")
        val vnext = JSONObject().put("address", p.address).put("port", p.port)
        val user = JSONObject().put("id", p.uuidOrPassword)
        if (p.flow.isNotEmpty()) user.put("flow", p.flow)
        user.put("encryption", "none")
        vnext.put("users", JSONArray().put(user))
        outbound.put("settings", JSONObject().put("vnext", JSONArray().put(vnext)))

        // streamSettings (tls/reality + транспорт) — заполните под ваш сценарий
        val stream = JSONObject().put("network", p.transport).put("security", p.security)
        outbound.put("streamSettings", stream)

        root.put("outbounds", JSONArray().put(outbound)
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
        return root.toString()
    }
}
