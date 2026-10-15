package ru.gidravpn.hydra.desktop

import ru.gidravpn.hydra.servers.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Тексты для людей без технической подготовки: каждая типовая ошибка объяснена и сопровождена советом. */
class FriendlyTextTest {
    private fun title(raw: String) = FriendlyText.explain(raw).title

    @Test fun commonErrorsAreExplainedInPlainWords() {
        assertEquals("Сервер не принял логин или пароль", title("Auth fail"))
        assertEquals("Сервер не принял логин или пароль", title("com.jcraft.jsch.JSchException: Auth cancel"))
        assertEquals("Сервер не ответил вовремя", title("java.net.SocketTimeoutException: connect timed out"))
        assertEquals("Сервер не ответил вовремя", title("failed to connect ... after 15000ms: connect failed: ETIMEDOUT timeout"))
        assertEquals("Сервер отказал в подключении", title("java.net.ConnectException: Connection refused"))
        assertEquals("Не удалось найти такой адрес", title("java.net.UnknownHostException: nodename nor servname provided"))
        assertEquals("Нет подключения к интернету", title("connect failed: ENETUNREACH (Network is unreachable)"))
        assertEquals("Загруженный файл оказался повреждён", title("Контрольная сумма /usr/local/bin/awg не совпала"))
        assertEquals("Сервер выглядит иначе, чем в прошлый раз", title("Отпечаток сервера изменился (был SHA256:a, сейчас SHA256:b) — возможна подмена"))
        assertEquals("Не получилось договориться о шифровании", title("Algorithm negotiation fail"))
        assertEquals("На сервере уже есть 3x-ui", title("На сервере уже стоит 3x-ui — подключите существующую"))
    }

    @Test fun unknownErrorKeepsRawTextAndGivesAdvice() {
        val f = FriendlyText.explain("совсем неожиданная ошибка 42")
        assertEquals("Что-то пошло не так", f.title)
        assertTrue("Скопировать подробности" in f.advice)
        assertEquals("совсем неожиданная ошибка 42", f.raw)
    }

    @Test fun everyAdviceIsActionable() {
        listOf("Auth fail", "timed out", "Connection refused", "unknown host", "ENETUNREACH", "checksum", "algorithm negotiation").forEach {
            val f = FriendlyText.explain(it)
            assertTrue(f.advice.length > 40, f.title)
            // без жаргона в первой строке
            assertFalse(listOf("SSH", "systemd", "NAT", "curl", "sha256").any { w -> w.lowercase() in f.title.lowercase() }, f.title)
        }
    }

    @Test fun checklistExplainsEachProblem() {
        val bad = Preflight("mips", "Ubuntu", root = false, systemd = false, portFree = false, internet = false, firewall = "none", issues = listOf("x"))
        val list = FriendlyText.checklist(bad)
        assertEquals(5, list.size)
        assertTrue(list.none { it.ok })
        assertTrue(list.all { it.help.length > 20 })
        val good = Preflight("amd64", "Debian 12", true, true, true, true, "ufw", emptyList())
        assertTrue(FriendlyText.checklist(good).all { it.ok })
    }

    @Test fun planIsInPlainSentencesForEveryKind() {
        ServerKind.entries.forEach { k ->
            val s = FriendlyText.planSentences(k, openFirewall = true)
            assertTrue(s.size >= 4, k.name)
            assertTrue(s.none { "iptables" in it || "systemctl" in it || "curl" in it }, k.name)
            assertTrue(FriendlyText.recommendation(k).isNotBlank())
        }
    }
}

class FriendlyTextEnglishTest {
    @Test fun englishVariantsExistAndDiffer() {
        listOf("Auth fail", "timed out", "Connection refused", "unknown host", "ENETUNREACH", "checksum", "algorithm negotiation", "что-то").forEach {
            val ru = FriendlyText.explain(it); val en = FriendlyText.explain(it, en = true)
            assertTrue(ru.title != en.title && en.title.none { c -> c in 'а'..'я' || c in 'А'..'Я' }, it)
            assertTrue(en.advice.none { c -> c in 'а'..'я' || c in 'А'..'Я' }, it)
        }
        ServerKind.entries.forEach { k ->
            assertTrue(FriendlyText.planSentences(k, true, en = true).all { s -> s.none { c -> c in 'а'..'я' || c in 'А'..'Я' } }, k.name)
            assertTrue(FriendlyText.recommendation(k, en = true).none { c -> c in 'а'..'я' })
        }
        val pre = Preflight("amd64", "Debian 12", true, true, true, true, "none", emptyList())
        assertTrue(FriendlyText.checklist(pre, en = true).all { it.title.none { c -> c in 'а'..'я' } })
    }
}
