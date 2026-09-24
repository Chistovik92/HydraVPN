package ru.gidravpn.hydra.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.gidravpn.hydra.data.model.ServerProfile
import ru.gidravpn.hydra.data.model.Subscription

@Database(
    entities = [ServerProfile::class, Subscription::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        /** 0.6.21: служебные данные подписки от панели (название, трафик, срок), автообновление, свёрнутость. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                listOf(
                    "serverTitle TEXT NOT NULL DEFAULT ''",
                    "uploadBytes INTEGER NOT NULL DEFAULT 0",
                    "downloadBytes INTEGER NOT NULL DEFAULT 0",
                    "totalBytes INTEGER NOT NULL DEFAULT 0",
                    "expireAt INTEGER NOT NULL DEFAULT 0",
                    "supportUrl TEXT NOT NULL DEFAULT ''",
                    "autoUpdate INTEGER NOT NULL DEFAULT 1",
                    "collapsed INTEGER NOT NULL DEFAULT 0",
                    "lastError TEXT NOT NULL DEFAULT ''",
                ).forEach { db.execSQL("ALTER TABLE subscriptions ADD COLUMN $it") }
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null
        // Фаза 7e: вторая проверка ВНУТРИ synchronized обязательна. Без неё два
        // потока, одновременно увидевшие null, по очереди собирали по своему
        // инстансу Room на один и тот же файл (второй затирал первый в
        // INSTANCE) — а репозитории в проекте создаются на каждый чих, в том
        // числе одновременно из UI и из VpnService.
        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "hydra.db")
                // Раньше здесь был fallbackToDestructiveMigration(): любое изменение схемы без
                // миграции молча стёрло бы все серверы. С 0.6.21 — только явные миграции.
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
        }
    }
}
