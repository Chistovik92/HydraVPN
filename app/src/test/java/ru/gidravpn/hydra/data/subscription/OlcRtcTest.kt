package ru.gidravpn.hydra.data.subscription

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OlcRtcTest {
    private val key = "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799"

    @Test fun parsesDocumentationExample() {
        val p = OlcRtcLink.parse("olcrtc://wbstream?datachannel@room-01#$key\$RU / olc free sub / IPv6")!!
        assertEquals("RU / olc free sub / IPv6", p.name)
        assertEquals("olcrtc", p.protocolId)
        assertEquals("room-01", p.address)
        assertEquals(key, p.uuidOrPassword)
        assertEquals("datachannel", p.transport)
        assertEquals("wbstream", JSONObject(p.extra).getString("provider"))
    }

    @Test fun parsesTransportPayload() {
        val p = OlcRtcLink.parse("olcrtc://jitsi?vp8channel<vp8-fps=30&vp8-batch=64>@https://meet.example.org/room#$key\$x")!!
        assertEquals("vp8channel", p.transport)
        assertEquals("https://meet.example.org/room", p.address)
        val params = JSONObject(p.extra).getJSONObject("params")
        assertEquals("30", params.getString("vp8-fps")); assertEquals("64", params.getString("vp8-batch"))
    }

    @Test fun rejectsBrokenLinks() {
        assertNull(OlcRtcLink.parse("olcrtc://telemost?datachannel@room"))          // нет ключа
        assertNull(OlcRtcLink.parse("olcrtc://telemost?datachannel#$key"))          // нет комнаты
        assertNull(OlcRtcLink.parse("vless://a@b:1"))
    }

    @Test fun roundTrip() {
        val src = "olcrtc://jitsi?vp8channel<vp8-fps=30&vp8-batch=64>@room-9#$key\$My room"
        val back = OlcRtcLink.build(OlcRtcLink.parse(src)!!)
        assertEquals(OlcRtcLink.parse(src), OlcRtcLink.parse(back))
    }

    @Test fun yamlForClient() {
        val p = OlcRtcLink.parse("olcrtc://jitsi?vp8channel<vp8-fps=30&vp8-batch=64>@https://meet.example.org/room#$key\$x")!!
        val y = OlcRtcConfigBuilder.build(p, 10809)
        listOf(
            "mode: cnc", "provider: \"jitsi\"", "key: \"$key\"", "transport: \"vp8channel\"",
            "dns: \"1.1.1.1:53\"", "port: 10809", "vp8:", "  fps: 30", "  batch_size: 64",
        ).forEach { assertTrue("«$it» не найдено в:\n$y", it in y) }
    }
}
