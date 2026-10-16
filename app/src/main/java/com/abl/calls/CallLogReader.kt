package com.abl.calls

import android.content.Context
import android.provider.CallLog
import org.json.JSONObject
import java.time.Instant

data class CallRow(
    val id: String,
    val number: String,
    val name: String,
    val type: String,
    val startedAt: Long,
    val durationSec: Long,
    val sim: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("device_call_id", id)
        .put("number", number)
        .put("name", name)
        .put("call_type", type)
        .put("started_at", Instant.ofEpochMilli(startedAt).toString())
        .put("duration_seconds", durationSec)
        .put("sim", sim)

    /** Last 10 digits, the same key the server matches on. */
    val numberKey: String get() = number.filter { it.isDigit() }.takeLast(10)
}

object CallLogReader {
    /** Calls with DATE >= sinceMillis, oldest first. Voicemail and "answered on another device" are skipped. */
    fun read(context: Context, sinceMillis: Long): List<CallRow> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        val rows = mutableListOf<CallRow>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} >= ?",
            arrayOf(sinceMillis.toString()),
            "${CallLog.Calls.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val type = when (c.getInt(3)) {
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> "rejected"
                    else -> null
                } ?: continue
                rows += CallRow(
                    id = c.getLong(0).toString(),
                    number = c.getString(1).orEmpty(),
                    name = c.getString(2).orEmpty(),
                    type = type,
                    startedAt = c.getLong(4),
                    durationSec = c.getLong(5),
                    sim = c.getString(6).orEmpty().take(40),
                )
            }
        }
        return rows
    }
}
