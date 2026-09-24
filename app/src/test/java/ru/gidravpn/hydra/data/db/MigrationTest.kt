package ru.gidravpn.hydra.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Миграции Room на настоящих схемах из app/schemas (Фаза 8). Схема v1 снята с 0.6.20 —
 * той версии, с которой пользователи обновлялись на 0.6.21. Robolectric даёт настоящий
 * SQLite на JVM, поэтому тест идёт без устройства, в том числе в CI.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private val dbName = "migration-test.db"

    @Test
    fun v1ToV2_keepsServersAndSubscriptions() {
        helper.createDatabase(dbName, 1).apply {
            execSQL(
                "INSERT INTO subscriptions (id, name, url, userAgent, lastUpdated, autoUpdateHours) " +
                    "VALUES (7, 'Панель', 'https://panel.example/sub/abc', 'Hydra', 1000, 12)"
            )
            execSQL(
                "INSERT INTO servers (id, name, protocolId, address, port, uuidOrPassword, flow, sni, " +
                    "transport, transportPath, security, alpn, fingerprint, extra, subscriptionId, pingMs, flag) " +
                    "VALUES (3, 'DE-1', 'vless', 'de.example', 443, 'uuid', '', '', 'tcp', '', 'reality', " +
                    "'', 'chrome', '', 7, 42, '🇩🇪')"
            )
            close()
        }

        // validateDroppedTables + сверка итоговой схемы с 2.json внутри хелпера.
        helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2).close()

        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java, dbName
        ).addMigrations(AppDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            runBlocking {
                val server = db.serverDao().getAll().single()
                assertEquals("DE-1", server.name)
                assertEquals(7L, server.subscriptionId)
                assertEquals(42, server.pingMs)

                val sub = db.subscriptionDao().getAll().single()
                assertEquals("https://panel.example/sub/abc", sub.url)
                // Новые колонки v2 получили значения по умолчанию из миграции.
                assertEquals("", sub.serverTitle)
                assertEquals(0L, sub.totalBytes)
                assertEquals(true, sub.autoUpdate)
                assertEquals(false, sub.collapsed)
                assertEquals("", sub.lastError)
            }
        } finally {
            db.close()
        }
    }
}
