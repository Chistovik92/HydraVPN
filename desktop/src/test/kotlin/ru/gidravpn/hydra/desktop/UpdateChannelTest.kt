package ru.gidravpn.hydra.desktop

import org.json.JSONArray
import org.json.JSONObject
import ru.gidravpn.hydra.update.UpdateChannel
import ru.gidravpn.hydra.update.UpdateFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Каналы обновлений 0.8.0: выпуски / релиз-кандидаты / разработчик (альфы и беты). */
class UpdateChannelTest {
    private fun rel(tag: String, pre: Boolean, draft: Boolean = false) = JSONObject().put("tag_name", "v$tag").put("prerelease", pre).put("draft", draft)
        .put("html_url", "https://github.com/Chistovik92/HydraVPN/releases/tag/v$tag").put("assets", JSONArray())

    private val list = UpdateFeed.parseList(JSONArray(listOf(
        rel("0.8.2-a", true), rel("0.8.1-rc1", true, draft = true), rel("0.8.1-b", true), rel("0.8.1-a", true),
        rel("0.8.0", false), rel("0.8.0-rc4", true), rel("0.7.20", false),
    )).toString())

    @Test fun channelsPickRightRelease() {
        assertEquals(6, list.size, "черновик пропущен")
        assertEquals("0.8.0", UpdateFeed.pickNewest(list, UpdateChannel.STABLE)?.version)
        assertEquals("0.8.0", UpdateFeed.pickNewest(list, UpdateChannel.RC)?.version, "rc1 0.8.1 — черновик, rc4 старше выпуска")
        assertEquals("0.8.2-a", UpdateFeed.pickNewest(list, UpdateChannel.DEV)?.version)
        assertNull(UpdateFeed.pickNewest(emptyList(), UpdateChannel.DEV))
    }

    @Test fun acceptsAndIds() {
        assertTrue(UpdateChannel.RC.accepts("0.8.1-rc2") && UpdateChannel.RC.accepts("0.8.1"))
        assertFalse(UpdateChannel.RC.accepts("0.8.1-b") || UpdateChannel.STABLE.accepts("0.8.1-rc1"))
        assertTrue(UpdateChannel.DEV.accepts("0.8.1-a2"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromId(null))
        assertEquals(UpdateChannel.DEV, UpdateChannel.fromId("dev"))
        // тот, кто на rc4, на обычном канале получает 0.8.0
        assertTrue(UpdateFeed.isNewer("0.8.0", "0.8.0-rc4"))
    }

    /** Выпуск 0.8.1-rc1 (пре-релиз): стоящему на 0.8.0 его предлагают «Релиз-кандидаты» и «Разработчик», обычный канал — нет. */
    @Test fun rc1OfferedFrom080OnBetaChannelsOnly() {
        val l = UpdateFeed.parseList(JSONArray(listOf(rel("0.8.1-rc1", true), rel("0.8.1-b", true), rel("0.8.0", false))).toString())
        assertEquals("0.8.0", UpdateFeed.pickNewest(l, UpdateChannel.STABLE)?.version)
        for (ch in listOf(UpdateChannel.RC, UpdateChannel.DEV)) {
            val r = UpdateFeed.pickNewest(l, ch)!!
            assertEquals("0.8.1-rc1", r.version, ch.id)
            assertTrue(UpdateFeed.isNewer(r.version, "0.8.0"), ch.id)
        }
        // стоящему на бете 0.8.1-b «Разработчик» тоже предлагает rc1
        assertTrue(UpdateFeed.isNewer("0.8.1-rc1", "0.8.1-b"))
    }

    /** 0.8.1-rc2: стоящему на rc1 и на 0.8.0 его предлагают «Релиз-кандидаты» и «Разработчик»; обычный канал остаётся на 0.8.0. */
    @Test fun rc2OfferedFromRc1And080() {
        val l = UpdateFeed.parseList(JSONArray(listOf(rel("0.8.1-rc2", true), rel("0.8.1-rc1", true), rel("0.8.0", false))).toString())
        assertEquals("0.8.0", UpdateFeed.pickNewest(l, UpdateChannel.STABLE)?.version)
        for (ch in listOf(UpdateChannel.RC, UpdateChannel.DEV)) assertEquals("0.8.1-rc2", UpdateFeed.pickNewest(l, ch)?.version, ch.id)
        assertTrue(UpdateFeed.isNewer("0.8.1-rc2", "0.8.1-rc1") && UpdateFeed.isNewer("0.8.1-rc2", "0.8.0") && !UpdateFeed.isNewer("0.8.1-rc1", "0.8.1-rc2"))
    }

    /** 0.8.2-a (альфа): предлагается только «Разработчику», стоящим на 0.8.1; «Релиз-кандидаты» и обычный канал остаются на 0.8.1. */
    @Test fun alpha082OnlyForDev() {
        val l = UpdateFeed.parseList(JSONArray(listOf(rel("0.8.2-a", true), rel("0.8.1", false), rel("0.8.1-rc2", true))).toString())
        assertEquals("0.8.1", UpdateFeed.pickNewest(l, UpdateChannel.STABLE)?.version)
        assertEquals("0.8.1", UpdateFeed.pickNewest(l, UpdateChannel.RC)?.version)
        assertEquals("0.8.2-a", UpdateFeed.pickNewest(l, UpdateChannel.DEV)?.version)
        assertTrue(UpdateFeed.isNewer("0.8.2-a", "0.8.1"))
    }
}
