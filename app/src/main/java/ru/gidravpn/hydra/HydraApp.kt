package ru.gidravpn.hydra

import android.app.Application

class HydraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCtx.filesDir = filesDir
    }
}
