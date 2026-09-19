package ru.gidravpn.hydra.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription

@Database(
    entities = [ServerProfile::class, Subscription::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        // Фаза 7e: вторая проверка ВНУТРИ synchronized обязательна. Без неё два
        // потока, одновременно увидевшие null, по очереди собирали по своему
        // инстансу Room на один и тот же файл (второй затирал первый в
        // INSTANCE) — а репозитории в проекте создаются на каждый чих, в том
        // числе одновременно из UI и из VpnService.
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "hydra.db")
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
        }
    }
}
