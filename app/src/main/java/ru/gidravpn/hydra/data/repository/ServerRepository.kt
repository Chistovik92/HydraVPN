package ru.gidravpn.hydra.data.repository

import android.content.Context
import ru.gidravpn.hydra.data.db.AppDatabase
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.subscription.LinkParser
import kotlinx.coroutines.flow.Flow

/** Единая точка доступа к серверам и подпискам. */
class ServerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(context)
    private val servers = db.serverDao()
    private val subs = db.subscriptionDao()
    private val fetcher = SubscriptionFetcher()

    val allServers: Flow<List<ServerProfile>> = servers.observeAll()
    val allSubscriptions: Flow<List<Subscription>> = subs.observeAll()

    suspend fun save(server: ServerProfile): Long = servers.upsert(server)
    suspend fun delete(server: ServerProfile) = servers.delete(server)
    suspend fun byId(id: Long) = servers.byId(id)

    /** Импорт одиночной ссылки (vless:// … ) из буфера обмена или deep-link. */
    suspend fun importLink(link: String): ServerProfile? =
        LinkParser.parseLine(link.trim())?.also { servers.upsert(it) }

    /** Пачка серверов из умного импорта (0.6.18): сколько реально сохранено. */
    suspend fun importProfiles(profiles: List<ServerProfile>): Int =
        profiles.count { runCatching { servers.upsert(it) }.isSuccess }

    /**
     * Добавить подписку и подтянуть её содержимое. Повторное добавление того же URL не плодит
     * дубликат — обновляется существующая подписка.
     */
    suspend fun addSubscription(name: String, url: String): Int {
        val existing = subs.getAll().firstOrNull { it.url.trim() == url.trim() }
        val subId = existing?.id ?: subs.upsert(Subscription(name = name, url = url))
        return refreshSubscription(subId)
    }

    data class RefreshResult(val total: Int, val added: Int, val removed: Int)

    /**
     * Перечитать подписку (0.6.21): синхронизация «как сказал хозяин подписки» — пропавшие серверы
     * удаляются, новые добавляются, остальные обновляются с сохранением id (SubscriptionSync).
     * Название, трафик и срок берутся из заголовков ответа панели.
     *
     * Страховка 7e остаётся: пустой ответ НЕ применяется. Панель, отдавшая 200 с пустым телом (или
     * сменившая формат), стёрла бы все серверы; истёкшая подписка обычно выглядит так же — лучше
     * оставить прежний список и показать ошибку.
     */
    suspend fun refreshSubscription(subId: Long): Int = refreshDetailed(subId).total

    suspend fun refreshDetailed(subId: Long): RefreshResult {
        val sub = subs.byId(subId) ?: throw IllegalStateException("подписка #$subId не найдена")
        val result = try {
            fetcher.fetch(sub.url, HydraDevice.headers(appContext), subId)
        } catch (e: Exception) {
            subs.update(sub.copy(lastError = e.message ?: e.javaClass.simpleName))
            throw e
        }
        if (result.profiles.isEmpty()) {
            val msg = "подписка не вернула ни одного сервера — прежний список сохранён"
            subs.update(sub.copy(lastError = msg))
            throw IllegalStateException(msg)
        }
        val plan = servers.syncSubscription(subId, result.profiles)
        val i = result.info
        subs.update(
            sub.copy(
                serverTitle = i.title,
                uploadBytes = i.upload, downloadBytes = i.download, totalBytes = i.total, expireAt = i.expire,
                supportUrl = i.supportUrl,
                autoUpdateHours = i.updateHours ?: sub.autoUpdateHours,
                lastUpdated = System.currentTimeMillis(),
                lastError = "",
            )
        )
        return RefreshResult(result.profiles.size, plan.toInsert.size, plan.toDelete.size)
    }

    suspend fun allSubscriptionsNow(): List<Subscription> = subs.getAll()
    suspend fun updateSubscription(sub: Subscription) = subs.update(sub)

    /** Удалить подписку вместе со всеми её серверами. */
    suspend fun deleteSubscription(sub: Subscription) {
        servers.deleteBySubscription(sub.id)
        subs.delete(sub)
    }
}
