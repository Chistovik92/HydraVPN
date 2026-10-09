package ru.gidravpn.hydra.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.gidravpn.hydra.data.geo.GeoRuntime
import ru.gidravpn.hydra.vpn.VpnState
import java.util.concurrent.TimeUnit

/** Автообновление geo-баз (0.7.13): раз в 6 часов; сама работа решает, пора ли (интервал из настроек). */
class GeoUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        runCatching { GeoRuntime.update(applicationContext, onlyIfDue = true) }
            .onFailure { VpnState.log("Ошибка: обновление geo-баз — ${it.message ?: it.javaClass.simpleName}") }
        return Result.success()
    }

    companion object {
        private const val NAME = "geo-auto-update"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<GeoUpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
