package com.nocry.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Sys.init(this)
        NLog.init(this)
        Notif.init(this)
        NLog.add("app", "iniciou")
    }
}
