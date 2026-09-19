package ru.gidravpn.hydra.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Подписка — URL, который отдаёт панель (x-ui, 3x-ui, PasarGuard, Remnawave, Marzban)
 * и содержит один или несколько профилей. См. docs/PANELS.md.
 *
 * С 0.6.21 хранит и то, что панель сообщает о себе в заголовках ответа (`profile-title`,
 * `subscription-userinfo`, `profile-update-interval`, `support-url`): название, трафик, срок.
 * Колонки добавлены миграцией 1 → 2 (AppDatabase), поэтому у всех новых — defaultValue.
 */
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Имя, введённое пользователем (или взятое из ссылки) — запасное, если панель не прислала своё. */
    val name: String,
    val url: String,
    val userAgent: String = "",            // устарело: UA теперь общий (HydraDevice), поле оставлено ради схемы
    val lastUpdated: Long = 0L,
    val autoUpdateHours: Int = 12,
    /** Название, которое отдала панель (`profile-title`); пустое — панель не прислала. */
    @ColumnInfo(defaultValue = "") val serverTitle: String = "",
    @ColumnInfo(defaultValue = "0") val uploadBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val downloadBytes: Long = 0,
    /** 0 — безлимит / не сообщается. */
    @ColumnInfo(defaultValue = "0") val totalBytes: Long = 0,
    /** Unix-время окончания подписки в секундах; 0 — бессрочно / не сообщается. */
    @ColumnInfo(defaultValue = "0") val expireAt: Long = 0,
    @ColumnInfo(defaultValue = "") val supportUrl: String = "",
    /** Автообновление по расписанию (WorkManager, SubscriptionUpdateWorker). */
    @ColumnInfo(defaultValue = "1") val autoUpdate: Boolean = true,
    /** Серверы свёрнуты под заголовок подписки. */
    @ColumnInfo(defaultValue = "0") val collapsed: Boolean = false,
    /** Текст последней ошибки обновления; пусто — последнее обновление прошло. */
    @ColumnInfo(defaultValue = "") val lastError: String = "",
) {
    /** То, что показываем в списке: название от панели, иначе пользовательское. */
    val displayName: String get() = serverTitle.ifBlank { name }
    val usedBytes: Long get() = uploadBytes + downloadBytes
}
