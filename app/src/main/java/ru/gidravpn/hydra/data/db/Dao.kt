package ru.gidravpn.hydra.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY sortIndex, id")
    fun observeAll(): Flow<List<ServerEntity>>
    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: Long): ServerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: ServerEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerEntity>)
    @Delete
    suspend fun delete(server: ServerEntity)
    @Query("DELETE FROM servers WHERE subscriptionId = :subId")
    suspend fun deleteBySubscription(subId: Long)
    @Query("SELECT * FROM servers WHERE subscriptionId = :subId ORDER BY sortIndex, id")
    suspend fun bySubscription(subId: Long): List<ServerEntity>
    @Delete
    suspend fun deleteAll(servers: List<ServerEntity>)
    @Update
    suspend fun updateAll(servers: List<ServerEntity>)
    @Query("SELECT * FROM servers ORDER BY sortIndex, id")
    suspend fun getAll(): List<ServerEntity>
    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    fun observeAll(): Flow<List<SubscriptionEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sub: SubscriptionEntity): Long
    @Delete
    suspend fun delete(sub: SubscriptionEntity)
    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun byId(id: Long): SubscriptionEntity?
    @Update
    suspend fun update(sub: SubscriptionEntity)
    @Query("SELECT * FROM subscriptions ORDER BY id")
    suspend fun getAll(): List<SubscriptionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(subs: List<SubscriptionEntity>)
    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}
