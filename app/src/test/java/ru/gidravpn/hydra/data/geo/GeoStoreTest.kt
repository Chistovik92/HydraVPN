package ru.gidravpn.hydra.data.geo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/** Динамический слой geo-баз (0.7.13): проверка, установка, откат, обновление, разбор .dat. */
class GeoStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun srs(version: Int = 2, size: Int = 64) = ByteArray(size) { if (it == 0) 'S'.code.toByte() else if (it == 1) 'R'.code.toByte() else if (it == 2) 'S'.code.toByte() else if (it == 3) version.toByte() else (it % 251).toByte() }
    private fun store() = GeoStore(tmp.newFolder())

    @Test fun installsValidSrsAndResolvesIt() {
        val s = store()
        assertNull(s.resolve(GeoKind.IP, "ru"))
        assertEquals(GeoStore.Outcome.INSTALLED, s.install(GeoKind.IP, "ru", srs(), false, "metacubex").outcome)
        val f = s.resolve(GeoKind.IP, "ru")
        assertNotNull(f); assertTrue(f!!.name.endsWith(".srs"))
        assertEquals(1, s.entries().size)
    }

    @Test fun sameContentIsUnchanged() {
        val s = store()
        s.install(GeoKind.IP, "ru", srs(), false, "a")
        assertEquals(GeoStore.Outcome.UNCHANGED, s.install(GeoKind.IP, "ru", srs(), false, "a").outcome)
    }

    @Test fun rejectsGarbageTooNewVersionAndTinyFiles() {
        val s = store()
        assertEquals(GeoStore.Outcome.REJECTED, s.install(GeoKind.IP, "x", "<html>blocked</html>".toByteArray(), false, "a").outcome)
        assertEquals(GeoStore.Outcome.REJECTED, s.install(GeoKind.IP, "x", srs(version = 9), false, "a").outcome)
        assertEquals(GeoStore.Outcome.REJECTED, s.install(GeoKind.IP, "x", byteArrayOf(1, 2), false, "a").outcome)
        assertNull(s.resolve(GeoKind.IP, "x"))
    }

    @Test fun refusesSuspiciouslyShrunkReplacement() {
        val s = store()
        s.install(GeoKind.IP, "ru", srs(size = 10_000), false, "a")
        val r = s.install(GeoKind.IP, "ru", srs(size = 500), false, "a")
        assertEquals(GeoStore.Outcome.REJECTED, r.outcome)
        assertEquals(10_000L, s.resolve(GeoKind.IP, "ru")!!.length())
    }

    @Test fun rollbackRestoresPreviousVersion() {
        val s = store()
        val v1 = srs(size = 1000); val v2 = srs(size = 1200).also { it[10] = 77 }
        s.install(GeoKind.IP, "ru", v1, false, "a")
        s.install(GeoKind.IP, "ru", v2, false, "a")
        assertEquals(1200L, s.resolve(GeoKind.IP, "ru")!!.length())
        assertTrue(s.entries().single().hasPrev)
        assertTrue(s.rollback(GeoKind.IP, "ru"))
        assertEquals(1000L, s.resolve(GeoKind.IP, "ru")!!.length())
        assertFalse(s.rollback(GeoKind.IP, "ru"))
    }

    @Test fun corruptedFileFallsBackToBundled() {
        val s = store()
        s.install(GeoKind.IP, "ru", srs(size = 300), false, "a")
        s.resolve(GeoKind.IP, "ru")!!.appendBytes(byteArrayOf(0))   // размер перестал совпадать с записью
        assertNull(s.resolve(GeoKind.IP, "ru"))
    }

    @Test fun textListBecomesSourceRuleSet() {
        val json = JSONObject(String(GeoStore.listToSource("# comment\n10.0.0.0/8\n1.2.3.4\n2001:db8::1\n", GeoKind.IP)))
        val cidr = json.getJSONArray("rules").getJSONObject(0).getJSONArray("ip_cidr")
        assertEquals(listOf("10.0.0.0/8", "1.2.3.4/32", "2001:db8::1/128"), (0 until cidr.length()).map { cidr.getString(it) })
        val dom = JSONObject(String(GeoStore.listToSource("example.com\nfull:a.b.org\nkeyword:casino\n", GeoKind.SITE))).getJSONArray("rules").getJSONObject(0)
        assertEquals("example.com", dom.getJSONArray("domain_suffix").getString(0))
        assertEquals("a.b.org", dom.getJSONArray("domain").getString(0))
        assertEquals("casino", dom.getJSONArray("domain_keyword").getString(0))
        val s = store()
        assertEquals(GeoStore.Outcome.INSTALLED, s.install(GeoKind.SITE, "mine", GeoStore.listToSource("example.com\n", GeoKind.SITE), true, "custom").outcome)
        assertTrue(s.resolve(GeoKind.SITE, "mine")!!.name.endsWith(".json"))
    }

    // --- protobuf .dat ---

    private fun varint(v: Long): ByteArray { val o = ByteArrayOutputStream(); var x = v; while (x >= 0x80) { o.write(((x and 0x7f) or 0x80).toInt()); x = x shr 7 }; o.write(x.toInt()); return o.toByteArray() }
    private fun ld(num: Int, b: ByteArray) = varint(((num shl 3) or 2).toLong()) + varint(b.size.toLong()) + b
    private fun vi(num: Int, v: Long) = varint((num shl 3).toLong()) + varint(v)

    private fun geoipDat(): ByteArray {
        fun cidr(ip: ByteArray, prefix: Int) = ld(2, ld(1, ip) + vi(2, prefix.toLong()))
        val ru = ld(1, "RU".toByteArray()) + cidr(byteArrayOf(5, 8, 0, 0), 16) + cidr(byteArrayOf(77, 88, 0, 0), 18)
        val us = ld(1, "US".toByteArray()) + cidr(byteArrayOf(8, 8, 8, 0), 24)
        return ld(1, us) + ld(1, ru)
    }

    private fun geositeDat(): ByteArray {
        fun dom(type: Long, v: String) = ld(2, vi(1, type) + ld(2, v.toByteArray()))
        val e = ld(1, "YOUTUBE".toByteArray()) + dom(2, "youtube.com") + dom(3, "youtu.be") + dom(0, "ytimg") + dom(1, "^yt\\d+\\.com$")
        return ld(1, ld(1, "OTHER".toByteArray()) + dom(2, "other.com")) + ld(1, e)
    }

    @Test fun datConverterExtractsOneCountryAndOneSite() {
        val ip = JSONObject(String(DatConverter.extract(geoipDat().inputStream(), GeoKind.IP, "ru")!!))
        val cidr = ip.getJSONArray("rules").getJSONObject(0).getJSONArray("ip_cidr")
        assertEquals(listOf("5.8.0.0/16", "77.88.0.0/18"), (0 until cidr.length()).map { cidr.getString(it) })
        assertNull(DatConverter.extract(geoipDat().inputStream(), GeoKind.IP, "zz"))

        val site = JSONObject(String(DatConverter.extract(geositeDat().inputStream(), GeoKind.SITE, "youtube")!!)).getJSONArray("rules").getJSONObject(0)
        assertEquals("youtube.com", site.getJSONArray("domain_suffix").getString(0))
        assertEquals("youtu.be", site.getJSONArray("domain").getString(0))
        assertEquals("ytimg", site.getJSONArray("domain_keyword").getString(0))
        assertEquals("^yt\\d+\\.com$", site.getJSONArray("domain_regex").getString(0))
    }

    // --- обновление ---

    private class FakeHttp(val files: Map<String, ByteArray>) : GeoHttp {
        val calls = mutableListOf<String>()
        override fun get(url: String, maxBytes: Long): ByteArray { calls += url; return files[url] ?: error("HTTP 404") }
    }

    @Test fun updaterUsesMirrorWhenPrimaryIsBlocked() {
        val s = store()
        val mirror = GeoSources.METACUBEX.ip[1].replace("{name}", "ru")
        val http = FakeHttp(mapOf(mirror to srs(size = 500)))
        val rep = GeoUpdater(s, http).update(GeoSettings(), listOf(GeoKind.IP to "ru"))
        assertEquals(1, rep.installed); assertEquals(0, rep.failed)
        assertEquals(2, http.calls.size)
        assertNotNull(s.resolve(GeoKind.IP, "ru"))
    }

    @Test fun updaterReportsFailureAndKeepsOldBase() {
        val s = store()
        s.install(GeoKind.IP, "ru", srs(size = 500), false, "a")
        val rep = GeoUpdater(s, FakeHttp(emptyMap())).update(GeoSettings(), listOf(GeoKind.IP to "ru", GeoKind.SITE to "youtube"))
        assertEquals(2, rep.failed)
        assertEquals(500L, s.resolve(GeoKind.IP, "ru")!!.length())
    }

    @Test fun updaterConvertsDatFromTheSelectedSource() {
        val s = store()
        val st = GeoSettings(ipSource = GeoSources.V2FLY_DAT.id, siteSource = GeoSources.V2FLY_DAT.id)
        val http = FakeHttp(mapOf(GeoSources.V2FLY_DAT.ip[0] to geoipDat(), GeoSources.V2FLY_DAT.site[0] to geositeDat()))
        val rep = GeoUpdater(s, http).update(st, listOf(GeoKind.IP to "ru", GeoKind.IP to "us", GeoKind.SITE to "youtube"))
        assertEquals(3, rep.installed)
        assertEquals(2, http.calls.size)   // .dat качается один раз на все страны
        assertTrue(s.resolve(GeoKind.IP, "us")!!.name.endsWith(".json"))
    }

    @Test fun customSourcesWork() {
        val s = store()
        val st = GeoSettings(custom = listOf(
            CustomGeoSource(GeoKind.IP, "mynet", "https://example.org/nets.txt", CustomGeoSource.TYPE_LIST),
            CustomGeoSource(GeoKind.SITE, "mysites", "https://example.org/sites.srs", CustomGeoSource.TYPE_SRS),
        ))
        val http = FakeHttp(mapOf("https://example.org/nets.txt" to "10.1.0.0/16\n".toByteArray(), "https://example.org/sites.srs" to srs(size = 200)))
        val rep = GeoUpdater(s, http).update(st, listOf(GeoKind.IP to "mynet", GeoKind.SITE to "mysites"))
        assertEquals(2, rep.installed)
    }

    @Test fun settingsRoundTrip() {
        val st = GeoSettings(false, 12, "sagernet", "runetfreedom", listOf("ru-blocked"), listOf("youtube"),
            listOf(CustomGeoSource(GeoKind.IP, "n", "https://x.y/z", CustomGeoSource.TYPE_LIST)))
        assertEquals(st, GeoSettings.fromJson(st.toJson()))
        assertEquals(GeoSettings(), GeoSettings.fromJson("не json"))
    }

    @Test fun catalogTemplatesAllHaveName() {
        GeoSources.ALL.filter { it.format == GeoFormat.SRS }.forEach { src ->
            GeoKind.entries.forEach { k -> assertTrue(src.id, src.templates(k).all { "{name}" in it }) }
        }
    }

    @Test fun neverTouchesFilesOutsideItsDirectory() {
        val dir = tmp.newFolder()
        val s = GeoStore(File(dir, "geo"))
        s.install(GeoKind.SITE, "../../evil", srs(), false, "a")
        assertTrue(dir.walkTopDown().filter { it.isFile }.all { it.path.startsWith(File(dir, "geo").path) })
    }
}
