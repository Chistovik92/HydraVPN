package ru.gidravpn.hydra.vpn

/** Расписание переподключения (Фаза 7b): вынесено из сервиса, чтобы проверяться юнит-тестом. */
object ReconnectPolicy {
    const val MAX_ATTEMPTS = 10
    private const val BASE_MS = 2_000L
    private const val MAX_BACKOFF_MS = 30_000L

    /** Пауза перед попыткой №n (с нуля): 2, 4, 8, 16, 30, 30… секунд. */
    fun backoffMs(n: Int): Long = minOf(BASE_MS shl minOf(n.coerceAtLeast(0), 10), MAX_BACKOFF_MS)
}
