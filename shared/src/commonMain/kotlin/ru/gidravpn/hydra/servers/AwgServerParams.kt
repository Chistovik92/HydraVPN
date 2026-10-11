package ru.gidravpn.hydra.servers

import java.security.SecureRandom

/** Поколение AmneziaWG, которое разворачивается на своём сервере (0.8.0). */
enum class AwgGeneration(val label: String) { V1_0("1.0"), V1_5("1.5"), V2_0("2.0"), V3("3.x") }

/**
 * Случайные параметры обфускации в допустимых пределах (docs/ROADMAP.md, 0.8.0):
 * Jmin < Jmax, S1 + 56 ≠ S2, H1–H4 различны и не пересекаются.
 * Результат — карта «ключ .conf в нижнем регистре → значение», как в профиле (`AwgParams`).
 *
 * Поколение 1.5 отличается от 1.0 сигнатурными пакетами I1–I5; мы их не задаём (пусто = выключено, как в апстриме),
 * так что 1.5 по набору параметров совпадает с 1.0 и совместим с клиентами обоих поколений.
 */
object AwgServerParams {
    private const val H_MIN = 5L           // 1–4 — типы сообщений обычного WireGuard
    private const val H_MAX = 0x7FFFFFFFL

    fun generate(gen: AwgGeneration, rnd: SecureRandom = SecureRandom()): Map<String, String> {
        val m = linkedMapOf<String, String>()
        val jmin = 4 + rnd.nextInt(30)
        m["jc"] = (4 + rnd.nextInt(8)).toString()
        m["jmin"] = jmin.toString()
        m["jmax"] = (jmin + 20 + rnd.nextInt(100)).toString()
        val s = pickS(rnd, if (gen >= AwgGeneration.V2_0) 4 else 2)
        s.forEachIndexed { i, v -> m["s${i + 1}"] = v.toString() }
        pickH(rnd, ranges = gen >= AwgGeneration.V2_0).forEachIndexed { i, v -> m["h${i + 1}"] = v }
        if (gen >= AwgGeneration.V3) {
            m["headerprotectionkey"] = X25519.randomKeyB64(rnd)
            m["contentpaddingaddition"] = rnd.nextInt(64).toString()
            m["randomtrailers"] = "on" // awg setconf понимает только on/off/0/1
        }
        return m
    }

    private fun pickS(rnd: SecureRandom, n: Int): List<Int> {
        while (true) {
            val v = List(n) { 15 + rnd.nextInt(120) }
            // S1 + 56 == S2 даёт одинаковую длину init- и response-пакетов (отпечаток); S3/S4 различны между собой
            val ok = v.toSet().size == n && v[0] + 56 != v[1] && (n < 4 || v[2] + 56 != v[3])
            if (ok) return v
        }
    }

    private fun pickH(rnd: SecureRandom, ranges: Boolean): List<String> {
        val span = (H_MAX - H_MIN) / 4
        // четыре непересекающиеся «четверти» диапазона, порядок перемешан
        val slots = (0..3).shuffled(kotlin.random.Random(rnd.nextLong()))
        return slots.map { q ->
            val lo = H_MIN + q * span
            val a = lo + (rnd.nextLong() and Long.MAX_VALUE) % (span / 2)
            if (!ranges) a.toString() else {
                val b = a + 1 + (rnd.nextLong() and Long.MAX_VALUE) % (span / 4)
                "$a-$b"
            }
        }
    }

    /** Проверка правил (для тестов и для чужих параметров). Возвращает список нарушений. */
    fun validate(p: Map<String, String>): List<String> {
        val bad = mutableListOf<String>()
        fun n(k: String) = p[k]?.toLongOrNull()
        val jmin = n("jmin")
        val jmax = n("jmax")
        if (jmin != null && jmax != null && jmin >= jmax) bad += "Jmin должен быть меньше Jmax"
        val s1 = n("s1")
        val s2 = n("s2")
        if (s1 != null && s2 != null && s1 + 56 == s2) bad += "S1 + 56 не должно равняться S2"
        val hs = (1..4).mapNotNull { p["h$it"] }
        if (hs.size == 4) {
            val iv = hs.map { v -> v.split('-').let { it[0].toLong() to it.last().toLong() } }
            for (i in iv.indices) for (j in i + 1 until iv.size)
                if (iv[i].first <= iv[j].second && iv[j].first <= iv[i].second) bad += "H${i + 1} и H${j + 1} пересекаются"
            if (iv.any { it.first < 5 }) bad += "H1–H4 должны быть не меньше 5"
        }
        return bad
    }
}
