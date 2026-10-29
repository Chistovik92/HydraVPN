package ru.gidravpn.hydra.data.repository

import android.content.Context
import ru.gidravpn.hydra.data.botaccount.BotSubscription
import ru.gidravpn.hydra.data.botaccount.BotSyncPlanner
import ru.gidravpn.hydra.data.db.AppDatabase
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.subscription.LinkParser
import ru.gidravpn.hydra.data.subscription.SubscriptionSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gidravpn.hydra.data.db.toEntity
import ru.gidravpn.hydra.data.db.toModel

/** Единая точка доступа к серверам и подпискам. */
class ServerRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(context)
    private val servers = db.serverDao()
    private val subs = db.subscriptionDao()
    private val fetcher = SubscriptionFetcher()

    val allServers: Flow<List<ServerProfile>> = servers.observeAll().map { list -> list.map { it.toModel() } }
    val allSubscriptions: Flow<List<Subscription>> = subs.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun save(server: ServerProfile): Long = servers.upsert(server.toEntity())
    suspend fun delete(server: ServerProfile) = servers.delete(server.toEntity())
    suspend fun byId(id: Long) = servers.byId(id)?.toModel()

    /**
     * 0.8.0-rc4: профили AmneziaWG, сохранённые до исправления как «WireGuard» (вставленный .conf, сервер из «Моих серверов»):
     * параметры обфускации в них есть, но тип был неверный, и sing-box не мог сделать рукопожатие. Тип исправляется на месте.
     * @return сколько профилей исправлено.
     */
    suspend fun fixMislabeledAmnezia(): Int = servers.getAll().map { it.toModel() }
        .filter { it.protocolId == ru.gidravpn.hydra.data.model.Protocol.WIREGUARD.id &&
            runCatching { ru.gidravpn.hydra.data.subscription.WireGuardParser.hasAwgParams(org.json.JSONObject(it.extra)) }.getOrDefault(false) }
        .count { runCatching { servers.upsert(it.copy(protocolId = ru.gidravpn.hydra.data.model.Protocol.AMNEZIAWG.id).toEntity()) }.isSuccess }

    /** Импорт одиночной ссылки (vless:// … ) из буфера обмена или deep-link. */
    suspend fun importLink(link: String): ServerProfile? =
        LinkParser.parseLine(link.trim())?.also { servers.upsert(it.toEntity()) }

    /** Пачка серверов из умного импорта (0.6.18): сколько реально сохранено. */
    suspend fun importProfiles(profiles: List<ServerProfile>): Int =
        profiles.count { runCatching { servers.upsert(it.toEntity()) }.isSuccess }

    /**
     * Добавить подписку и подтянуть её содержимое. Повторное добавление того же URL не плодит
     * дубликат — обновляется существующая подписка.
     */
    suspend fun addSubscription(name: String, url: String): Int {
        val existing = subs.getAll().map { it.toModel() }.firstOrNull { it.url.trim() == url.trim() }
        val subId = existing?.id ?: subs.upsert(Subscription(name = name, url = url).toEntity())
        return refreshSubscription(subId)
    }

    /** Итог синхронизации с аккаунтом бота: подписок взято, серверов в них, подписок с ошибкой. */
    data class BotSyncResult(val subscriptions: Int, val servers: Int, val failed: Int)

    /**
     * Завести подписки, выданные в боте «Радар». Берутся только ссылки подписок в рабочем состоянии
     * ([BotSubscription.importable]), связь с подпиской в приложении — по панели бота (0.6.27.2), поэтому
     * смена `url` обновляет ту же подписку, а не заводит вторую ([BotSyncPlanner]). Выключенные в боте
     * подписки перестают автообновляться и помечаются; пропавшие из бота не удаляются: решение об этом
     * остаётся за человеком.
     */
    suspend fun syncBotSubscriptions(items: List<BotSubscription>): BotSyncResult {
        var subscriptions = 0
        var servers = 0
        var failed = 0
        for (step in BotSyncPlanner.plan(subs.getAll().map { it.toModel() }, items)) {
            when (step) {
                is BotSyncPlanner.Step.Disable ->
                    subs.update(step.sub.copy(autoUpdate = false, lastError = BotSyncPlanner.DISABLED_MARK).toEntity())
                is BotSyncPlanner.Step.Add -> runCatching {
                    val id = subs.upsert(Subscription(name = step.item.title, url = step.item.url, botPanel = step.item.panel).toEntity())
                    refreshSubscription(id).also { applyBotQuota(id, step.item) }
                }.fold(onSuccess = { subscriptions++; servers += it }, onFailure = { failed++ })
                is BotSyncPlanner.Step.Update -> runCatching {
                    val sub = step.sub
                    subs.update(
                        sub.copy(
                            url = step.item.url,
                            botPanel = step.item.panel,
                            autoUpdate = sub.autoUpdate || sub.lastError == BotSyncPlanner.DISABLED_MARK,
                        ).toEntity()
                    )
                    refreshSubscription(sub.id).also { applyBotQuota(sub.id, step.item) }
                }.fold(onSuccess = { subscriptions++; servers += it }, onFailure = { failed++ })
            }
        }
        return BotSyncResult(subscriptions, servers, failed)
    }


    /** Срок и трафик из ответа бота — запасной источник: заголовки панели при обновлении подписки главнее. */
    private suspend fun applyBotQuota(subId: Long, item: BotSubscription) {
        val s = subs.byId(subId)?.toModel() ?: return
        if (s.expireAt != 0L || s.totalBytes != 0L) return
        subs.update(s.copy(expireAt = item.expire, totalBytes = item.trafficLimit, downloadBytes = item.trafficUsed, uploadBytes = 0).toEntity())
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
        val sub = subs.byId(subId)?.toModel() ?: throw IllegalStateException("подписка #$subId не найдена")
        val result = try {
            fetcher.fetch(sub.url, HydraDevice.headers(appContext), subId)
        } catch (e: Exception) {
            subs.update(sub.copy(lastError = e.message ?: e.javaClass.simpleName).toEntity())
            throw e
        }
        if (result.profiles.isEmpty()) {
            val msg = "подписка не вернула ни одного сервера — прежний список сохранён"
            subs.update(sub.copy(lastError = msg).toEntity())
            throw IllegalStateException(msg)
        }
        val plan = SubscriptionSync.plan(subId, servers.bySubscription(subId).map { it.toModel() }, result.profiles, preserveMissing = result.partial)
        if (plan.toDelete.isNotEmpty()) servers.deleteAll(plan.toDelete.map { it.toEntity() })
        plan.toUpdate.forEach { servers.upsert(it.toEntity()) }
        plan.toInsert.forEach { servers.upsert(it.toEntity()) }
        val i = result.info
        subs.update(
            sub.copy(
                serverTitle = i.title,
                uploadBytes = i.upload, downloadBytes = i.download, totalBytes = i.total, expireAt = i.expire,
                supportUrl = i.supportUrl,
                autoUpdateHours = i.updateHours ?: sub.autoUpdateHours,
                lastUpdated = System.currentTimeMillis(),
                lastError = if (result.partial) "часть строк не разобрана — отсутствующие серверы сохранены" else "",
            ).toEntity()
        )
        return RefreshResult(result.profiles.size, plan.toInsert.size, plan.toDelete.size)
    }

    suspend fun allSubscriptionsNow(): List<Subscription> = subs.getAll().map { it.toModel() }
    suspend fun updateSubscription(sub: Subscription) = subs.update(sub.toEntity())

    /** Удалить подписку вместе со всеми её серверами. */
    suspend fun deleteSubscription(sub: Subscription) {
        servers.deleteBySubscription(sub.id)
        subs.delete(sub.toEntity())
    }
}
