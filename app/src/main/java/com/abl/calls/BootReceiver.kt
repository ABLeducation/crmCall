package com.abl.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the call-log trigger after a reboot or app update (WorkManager keeps the periodic job itself). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Prefs(context).signedIn) Scheduler.start(context)
    }
}
