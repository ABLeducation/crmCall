package com.abl.calls

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        if (Prefs(this).signedIn) Scheduler.start(this)
    }
}
