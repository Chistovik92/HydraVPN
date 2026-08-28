package ru.gidravpn.hydra

import android.content.Context
import java.io.File

/** Лёгкий держатель контекста для доступа из flavor-специфичного кода (native runtime). */
object AppCtx {
    lateinit var filesDir: File

    /** Контекст приложения (для платформенных методов HydraPlatformInterface). */
    @Volatile var appContext: Context? = null
}
