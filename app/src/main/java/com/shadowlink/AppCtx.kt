package com.shadowlink

import java.io.File

/** Лёгкий держатель контекста для доступа из flavor-специфичного кода (native runtime). */
object AppCtx {
    lateinit var filesDir: File
}
