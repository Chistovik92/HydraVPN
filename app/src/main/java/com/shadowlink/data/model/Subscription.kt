package com.shadowlink.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Подписка — URL, который отдаёт панель (x-ui, 3x-ui, PasarGuard, Remnawave)
 * и содержит один или несколько профилей. См. docs/PANELS.md.
 */
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val userAgent: String = "ShadowLink/0.1",   // некоторые панели отдают разный формат по UA
    val lastUpdated: Long = 0L,
    val autoUpdateHours: Int = 12
)
