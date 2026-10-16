package com.abl.calls

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlin.math.abs

/**
 * Android doesn't let third-party apps record calls, so — like Runo — we pick up the
 * recordings the phone's own dialer saves (Samsung, Xiaomi, OnePlus, Oppo/Realme, Vivo …)
 * from shared storage, matched to a call by time and, where the file name has it, number.
 * Phones whose dialer keeps recordings private (e.g. Google Phone) just won't have any.
 */
object Recordings {
    data class Found(val uri: Uri, val name: String, val mime: String, val size: Long)

    private val HINTS = listOf("call", "record", "phonerecord", "call_rec", "callrec")
    private const val MAX_BYTES = 25L * 1024 * 1024

    fun find(context: Context, call: CallRow): Found? {
        if (call.durationSec <= 0) return null
        val endSec = (call.startedAt / 1000) + call.durationSec
        val startSec = call.startedAt / 1000
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            pathColumn,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )
        // A recording is written during the call and closed when it ends.
        val selection = "${MediaStore.MediaColumns.DATE_MODIFIED} BETWEEN ? AND ?"
        val args = arrayOf((startSec - 30).toString(), (endSec + 180).toString())
        var best: Found? = null
        var bestScore = Long.MAX_VALUE
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    val path = c.getString(2).orEmpty()
                    val haystack = "$path/$name".lowercase()
                    val digits = name.filter { it.isDigit() }
                    val numberInName = call.numberKey.length == 10 && digits.contains(call.numberKey)
                    if (!numberInName && HINTS.none { haystack.contains(it) }) continue
                    val size = c.getLong(5)
                    if (size <= 0 || size > MAX_BYTES) continue
                    // Closest to the call's end wins; a number in the file name beats any timing.
                    val score = abs(c.getLong(3) - endSec) - if (numberInName) 1_000_000 else 0
                    if (score < bestScore) {
                        bestScore = score
                        best = Found(
                            uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                            name = name.ifEmpty { "recording.m4a" },
                            mime = c.getString(4) ?: "audio/mp4",
                            size = size,
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            return null // audio permission not granted
        }
        return best
    }

    fun read(context: Context, found: Found): ByteArray? =
        context.contentResolver.openInputStream(found.uri)?.use { it.readBytes() }
}
