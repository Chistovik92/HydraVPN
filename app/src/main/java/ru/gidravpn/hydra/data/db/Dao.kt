package ru.gidravpn.hydra.data.db

import androidx.room.*
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY id")
    fun observeAll(): Flow<List<ServerProfile>>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: Long): ServerProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerProfile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerProfile>)

    @Delete
    suspend fun delete(server: ServerProfile)

    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)

    /**
     * Замена содержимого подписки одним куском (Фаза 7e). Раньше репозиторий
     * звал `deleteBySubscription()` и `upsertAll()` подряд, вне транзакции:
     * падение или снятие процесса между ними оставляло подписку пустой —
     * серверы стёрты, новые не записаны.
     */
    @Transaction
    suspend fun replaceSubscriptionServers(subId: Long, servers: List<ServerProfile>) {
        deleteBySubscription(subId)
        upsertAll(servers)
    }

    @Query("SELECT * FROM servers ORDER BY id")
    suspend fun getAll(): List<ServerProfile>

    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeAll(): Flow<List<Subscription>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sub: Subscription): Long

    @Delete
    suspend fun delete(sub: Subscription)

    @Query("SELECT * FROM subscriptions ORDER BY id")
    suspend fun getAll(): List<Subscription>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(subs: List<Subscription>)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}
