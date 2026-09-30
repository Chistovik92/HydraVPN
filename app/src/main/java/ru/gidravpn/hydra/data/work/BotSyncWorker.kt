package ru.gidravpn.hydra.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.gidravpn.hydra.data.botaccount.BotAccountApi
import ru.gidravpn.hydra.data.botaccount.BotAccountException
import ru.gidravpn.hydra.data.botaccount.BotAccountStore
import ru.gidravpn.hydra.data.repository.ServerRepository
import ru.gidravpn.hydra.vpn.VpnState
import java.util.concurrent.TimeUnit

/**
 * Фоновая синхронизация с аккаунтом бота «Радар» (API_APPS.md: «раз в несколько часов»).
 * Если устройство не подключено к боту — ничего не делает. Токен, отозванный в боте (401),
 * стирается; сеть и прочие ошибки не страшны — следующий запуск повторит.
 */
class BotSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = BotAccountStore(applicationContext)
        if (!store.linked) return Result.success()
        val token = store.token ?: return Result.success()
        try {
            val items = BotAccountApi().subscriptions(store.server, token)
            val r = ServerRepository(applicationContext).syncBotSubscriptions(items)
            VpnState.log("Аккаунт бота (авто): подписок ${r.subscriptions}, серверов ${r.servers}, ошибок ${r.failed}")
        } catch (e: BotAccountException) {
            if (e.code == 401) {
                store.clear()
                VpnState.log("Аккаунт бота: устройство отключено в боте, токен удалён")
            } else {
                VpnState.log("Ошибка: синхронизация с ботом — ${e.message}")
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "bot-account-sync"

        /** Идемпотентно: KEEP не перезапускает уже запланированную работу. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BotSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
