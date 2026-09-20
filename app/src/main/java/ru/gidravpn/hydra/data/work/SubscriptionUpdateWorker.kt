package ru.gidravpn.hydra.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.vpn.VpnState
import java.util.concurrent.TimeUnit

/**
 * Автообновление подписок (0.6.21). Раз в час проверяет, какие подписки «созрели»: включено
 * автообновление и прошло [Subscription.autoUpdateHours] (по умолчанию 12, либо сколько просит
 * панель заголовком `profile-update-interval`). Обновление — та же синхронизация, что и по кнопке.
 */
class SubscriptionUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = ServerRepository(applicationContext)
        val now = System.currentTimeMillis()
        // Чтение списка тоже может упасть (повреждённая база) — необработанное
        // исключение из doWork() уводит работу в retry и ничего не пишет в журнал.
        val due = runCatching { repo.allSubscriptionsNow() }
            .onFailure { VpnState.log("Ошибка: автообновление подписок — ${it.message ?: it.javaClass.simpleName}") }
            .getOrElse { emptyList() }
            .filter { isDue(it, now) }
        due.forEach { sub ->
            runCatching { repo.refreshDetailed(sub.id) }
                .onSuccess { VpnState.log("Подписка «${sub.displayName}» обновлена автоматически: ${it.total} серв. (+${it.added} / −${it.removed})") }
                .onFailure { VpnState.log("Ошибка: автообновление «${sub.displayName}» — ${it.message ?: it.javaClass.simpleName}") }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "subscription-auto-update"

        internal fun isDue(s: Subscription, now: Long): Boolean =
            s.autoUpdate && now - s.lastUpdated >= s.autoUpdateHours.coerceAtLeast(1) * 3_600_000L

        /** Идемпотентно: KEEP не перезапускает уже запланированную работу. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
