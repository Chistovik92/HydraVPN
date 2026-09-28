package ru.gidravpn.hydra.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import ru.gidravpn.hydra.data.model.Subscription

/**
 * Room entity для Subscription. Соответствует доменной модели в commonMain,
 * но с аннотациями Room. Маппинг между доменной моделью и сущностью
 * делается в репозитории.
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Имя, введённое пользователем (или взятое из ссылки) — запасное, если панель не прислала своё. */
    val name: String,
    val url: String,
    val userAgent: String = "",
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
    fun toDomain(): Subscription = Subscription(
        id = id,
        name = name,
        url = url,
        userAgent = userAgent,
        lastUpdated = lastUpdated,
        autoUpdateHours = autoUpdateHours,
        serverTitle = serverTitle,
        uploadBytes = uploadBytes,
        downloadBytes = downloadBytes,
        totalBytes = totalBytes,
        expireAt = expireAt,
        supportUrl = supportUrl,
        autoUpdate = autoUpdate,
        collapsed = collapsed,
        lastError = lastError,
    )

    companion object {
        fun fromDomain(subscription: Subscription): SubscriptionEntity = SubscriptionEntity(
            id = subscription.id,
            name = subscription.name,
            url = subscription.url,
            userAgent = subscription.userAgent,
            lastUpdated = subscription.lastUpdated,
            autoUpdateHours = subscription.autoUpdateHours,
            serverTitle = subscription.serverTitle,
            uploadBytes = subscription.uploadBytes,
            downloadBytes = subscription.downloadBytes,
            totalBytes = subscription.totalBytes,
            expireAt = subscription.expireAt,
            supportUrl = subscription.supportUrl,
            autoUpdate = subscription.autoUpdate,
            collapsed = subscription.collapsed,
            lastError = subscription.lastError,
        )
    }
}