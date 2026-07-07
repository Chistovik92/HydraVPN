package com.shadowlink.data.repository

import android.content.Context
import com.shadowlink.data.db.AppDatabase
import com.shadowlink.data.model.ServerProfile
import com.shadowlink.data.model.Subscription
import com.shadowlink.data.subscription.LinkParser
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

    /** Добавить подписку и подтянуть её содержимое. */
    suspend fun addSubscription(name: String, url: String): Int {
        val subId = subs.upsert(Subscription(name = name, url = url))
        return refreshSubscription(subId, url)
    }

    suspend fun refreshSubscription(subId: Long, url: String): Int {
        val profiles = fetcher.fetch(url, "ShadowLink/0.1", subId)
        servers.deleteBySubscription(subId)
        servers.upsertAll(profiles)
        return profiles.size
    }
}
