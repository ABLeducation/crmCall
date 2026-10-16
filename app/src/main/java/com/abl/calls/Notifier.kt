package com.abl.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    private const val CHANNEL_CALLS = "post_call"
    private const val CHANNEL_STATUS = "status"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "After a call", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks for notes right after a call"
            },
        )
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Sync status", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun addNotes(context: Context, phoneCallId: Int, row: CallRow, schoolName: String, intent: Intent) {
        val who = schoolName.ifEmpty { row.name.ifEmpty { row.number } }
        val pending = PendingIntent.getActivity(
            context, phoneCallId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Add notes · $who")
            .setContentText("${row.type.replaceFirstChar { it.uppercase() }} call${if (row.durationSec > 0) " · ${formatDuration(row.durationSec)}" else ""}. Tap to log it.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        notify(context, phoneCallId, notification)
    }

    fun cancel(context: Context, phoneCallId: Int) = NotificationManagerCompat.from(context).cancel(phoneCallId)

    fun signedOut(context: Context, message: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_call)
            .setContentTitle("Call tracking stopped")
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        notify(context, 1, notification)
    }

    private fun notify(context: Context, id: Int, notification: android.app.Notification) {
        if (Sync.granted(context, Manifest.permission.POST_NOTIFICATIONS) || android.os.Build.VERSION.SDK_INT < 33) {
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (e: SecurityException) {
                // Notifications turned off.
            }
        }
    }
}

fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s.toString().padStart(2, '0')}s" else "${s}s"
}
