package com.shadowlink

import android.app.Application

class ShadowLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.filesDir = filesDir
    }
}
