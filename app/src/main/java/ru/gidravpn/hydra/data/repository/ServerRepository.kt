package ru.gidravpn.hydra.data.repository

import android.content.Context
import ru.gidravpn.hydra.data.db.AppDatabase
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import ru.gidravpn.hydra.data.subscription.LinkParser
import kotlinx.coroutines.flow.Flow

/** Единая точка доступа к серверам и подпискам. */
class ServerRepository(context: Context) {
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

    /** Добавить подписку и подтянуть её содержимое. */
    suspend fun addSubscription(name: String, url: String): Int {
        val subId = subs.upsert(Subscription(name = name, url = url))
        return refreshSubscription(subId, url)
    }

    /**
     * Перечитать подписку. Фаза 7e — две страховки от потери серверов:
     *  - пустой разбор НЕ применяется: панель, отдавшая 200 с пустым телом
     *    (или сменившая формат, который мы не разобрали), раньше молча стирала
     *    все серверы подписки. Бросаем исключение — вызывающий покажет ошибку,
     *    а старый список останется на месте;
     *  - удаление и вставка — одной транзакцией, а не двумя вызовами подряд.
     */
    suspend fun refreshSubscription(subId: Long, url: String): Int {
        val profiles = fetcher.fetch(url, "Hydra/0.5", subId)
        if (profiles.isEmpty()) {
            throw IllegalStateException("подписка не вернула ни одного сервера — прежний список сохранён")
        }
        servers.replaceSubscriptionServers(subId, profiles)
        return profiles.size
    }
}
