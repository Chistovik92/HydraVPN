package ru.gidravpn.hydra.data.dpi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Автоподбор стратегии обхода DPI (идея ByeByeDPI, romanvht/ByeByeDPI → «Проверка прокси»), но для человека, а не для знатока:
 * выбираешь, что хочешь открыть (YouTube, Discord, Telegram…), приложение само перебирает стратегии и показывает лучшие
 * с понятным итогом «открылось 11 из 12» по каждой группе.
 *
 * Для каждой стратегии поднимается `ciadpi`, через него открываются сайты выбранных групп по HTTPS, и выигрывает та, где открылось
 * больше. Проверка идёт через локальный SOCKS5, поэтому DPI провайдера видит ровно то же, что увидит реальный трафик.
 * Сначала — проверка «без обхода»: если всё и так открывается, подбирать нечего.
 */
object DpiProbe {

    /** Итог по группе сайтов: открылось [ok] из [total]. */
    data class Group(val name: String, val ok: Int, val total: Int)

    data class Result(val strategy: String, val ok: Int, val total: Int, val groups: List<Group> = emptyList()) {
        val ratio get() = if (total == 0) 0.0 else ok.toDouble() / total
        val percent get() = (ratio * 100).toInt()
    }

    /** Режим: быстрый — первые [QUICK] стратегий, полный — все. */
    const val QUICK = 15

    /** Один запрос: любой HTTP-ответ (даже 403/404) — сайт достижим, значит, ClientHello дошёл. */
    fun reachable(site: String, port: Int, timeoutMs: Int): Boolean {
        val url = runCatching { URL(if (site.startsWith("http")) site else "https://$site") }.getOrNull() ?: return false
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        var c: HttpURLConnection? = null
        return try {
            c = url.openConnection(proxy) as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("Connection", "close")
            c.responseCode > 0
        } catch (_: Exception) { false } finally { runCatching { c?.disconnect() } }
    }

    /** Достижимость всех сайтов групп через уже запущенный прокси на [port]. */
    private suspend fun measure(strategy: String, groups: Map<String, List<String>>, port: Int, timeoutMs: Int, concurrency: Int, requests: Int = 1): Result =
        coroutineScope {
            val sem = Semaphore(concurrency)
            // Сайт открыт, если ответило большинство из [requests] попыток (при одной — она сама).
            val need = requests / 2 + 1
            val checks = groups.map { (g, sites) ->
                g to sites.map { s -> async { var ok = 0; repeat(requests) { if (sem.withPermit { reachable(s, port, timeoutMs) }) ok++ }; ok >= need } }
            }
            val per = checks.map { (g, list) -> Group(g, list.awaitAll().count { it }, list.size) }
            Result(strategy, per.sumOf { it.ok }, per.sumOf { it.total }, per)
        }

    /**
     * @param groups группа → сайты (см. [DpiStrategies.SITES]).
     * @param start запускает `ciadpi` с этими аргументами и возвращает то, чем его остановить (бросает, если не поднялся).
     * @param onBaseline результат проверки без обхода (приходит первым).
     * @param onResult после каждой стратегии: (номер с 1, всего, результат).
     * @param stopAtFull остановиться, как только нашлась стратегия, открывающая всё.
     * @return результаты по убыванию доли открытых (при равенстве — в порядке списка).
     */
    suspend fun run(
        strategies: List<String>,
        groups: Map<String, List<String>>,
        port: Int,
        timeoutMs: Int = 3500,
        concurrency: Int = 16,
        stopAtFull: Boolean = true,
        requests: Int = 1,
        /** Пауза между проверками стратегий, мс. */
        delayMs: Long = 0,
        /** SNI для `{sni}` в стратегиях. */
        sni: String = DpiStrategies.FAKE_SNI,
        start: (List<String>) -> AutoCloseable,
        onBaseline: (Result) -> Unit = {},
        onResult: (Int, Int, Result) -> Unit = { _, _, _ -> },
    ): List<Result> = withContext(Dispatchers.IO) {
        val bare = listOf("-i", "127.0.0.1", "-p", port.toString())
        val baseline = runCatching { start(bare) }.fold(
            onSuccess = { p ->
                try {
                    // Прогрев: первое соединение в свежем процессе (классы, DNS, TLS) медленнее остальных и ложно «не открывается».
                    groups.values.firstOrNull()?.firstOrNull()?.let { reachable(it, port, timeoutMs * 2) }
                    measure("", groups, port, timeoutMs * 2, concurrency, requests)
                } finally { runCatching { p.close() } }
            },
            onFailure = { Result("", 0, groups.values.sumOf { it.size }) },
        )
        onBaseline(baseline)

        val out = mutableListOf<Result>()
        for ((i, s) in strategies.withIndex()) {
            ensureActive()
            val r = runCatching { start(DpiArgs.build(s, port, sni = sni)) }.fold(
                onSuccess = { p -> try { measure(s, groups, port, timeoutMs, concurrency, requests) } finally { runCatching { p.close() } } },
                onFailure = { Result(s, 0, groups.values.sumOf { it.size }) },
            )
            out += r
            onResult(i + 1, strategies.size, r)
            if (stopAtFull && r.total > 0 && r.ok == r.total) break
            if (delayMs > 0 && i < strategies.size - 1) delay(delayMs)
        }
        out.sortedByDescending { it.ratio }
    }
}
