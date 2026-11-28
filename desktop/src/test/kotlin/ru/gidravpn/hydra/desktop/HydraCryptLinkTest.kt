package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.data.subscription.HydraCryptLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * hydra://crypt1/… (0.8.4-rc1): формат зашифрованных ссылок подписки. Закреплённые векторы
 * обязаны совпадать байт-в-байт с HydraCryptLinkTests.swift (iOS) — ссылка, сделанная на
 * Android/ПК, открывается на iOS и наоборот. Векторы собраны с детерминированным IV 00..0B;
 * продакшн-encode использует случайный IV.
 */
class HydraCryptLinkTest {
    private val url = "https://sub.your-provider.example/abc123token"
    private val iv = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)

    @Test fun keyFingerprintGuard() {
        // Страж: если ключ K1 сменят (crypt2/), отпечаток и векторы перестанут сходиться — тест
        // напомнит обновить KEY_FINGERPRINT и векторы во всех клиентах.
        assertEquals(HydraCryptLink.KEY_FINGERPRINT, HydraCryptLink.fingerprintHex())
    }

    @Test fun deterministicVectors() {
        assertEquals(
            "hydra://crypt1/AAECAwQFBgcICQoLXvSqhkQ4OMak3NAMxV8zhGccCiBpeMMej6bm734-2dL6t_puyw3yDDPthpSuGbapvQxloCKNuVVCRzsYqwQilUhjIaP5yna2IFTQ0HI",
            HydraCryptLink.encodeDeterministic(url, null, iv))
        assertEquals(
            "hydra://crypt1/AAECAwQFBgcICQoLXvSx1hI4T53s29EelEk-3mYFSjQyf8IY0qWuryckyNWm4PBj2E3yEjm0jpKoWqr_sQJjtSDKuhZWBjAb5Q6C_MJbhfHGrWCqnlBY_mBWzCCjWUgZu-LaDDPy",
            HydraCryptLink.encodeDeterministic(url, "My sub", iv))
    }

    @Test fun roundtripWithRandomIv() {
        val link = HydraCryptLink.encode(url, "  Провайдер  ")!!
        val d = HydraCryptLink.decode(link)!!
        assertEquals(url, d.url)
        assertEquals("Провайдер", d.name)
        // Случайный IV: две кодировки одной подписки различаются, обе расшифровываются.
        val link2 = HydraCryptLink.encode(url, "Провайдер")!!
        assertNotEquals(link, link2)
        assertEquals(url, HydraCryptLink.decode(link2)!!.url)
    }

    @Test fun rejectsBadInput() {
        assertNull(HydraCryptLink.encode("vless://not-a-subscription"))
        assertNull(HydraCryptLink.encode(""))
        assertNull(HydraCryptLink.decode("hydra://crypt1/"))
        assertNull(HydraCryptLink.decode("hydra://crypt1/AAAA"))
        assertNull(HydraCryptLink.decode("vless://example.com"))
        // GCM-тег испорчен: замена последнего символа base64url меняет последний байт тега.
        val v1 = HydraCryptLink.encodeDeterministic(url, null, iv)!!
        assertNull(HydraCryptLink.decode(v1.dropLast(1) + if (v1.endsWith("A")) "B" else "A"))
    }
}