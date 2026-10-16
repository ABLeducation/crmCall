package com.abl.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Uploads new call-log rows. Runs (1) within seconds of the call log changing — a
 * content-URI trigger, re-armed after every run — and (2) every 15 minutes as a
 * fallback, so nothing is lost if the phone was offline.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val triggered = tags.contains(TAG_TRIGGER)
        try {
            Sync.run(applicationContext)
            Result.success()
        } catch (e: Api.Unauthorized) {
            Prefs(applicationContext).signOut()
            Notifier.signedOut(applicationContext, e.message ?: "Signed out.")
            Result.success()
        } catch (e: IOException) {
            Prefs(applicationContext).lastError = e.message
            if (triggered) Result.success() else Result.retry()
        } finally {
            if (triggered && Prefs(applicationContext).signedIn) Scheduler.armTrigger(applicationContext, append = true)
        }
    }

    companion object {
        const val TAG_TRIGGER = "call-log-trigger"
    }
}

object Scheduler {
    private const val PERIODIC = "call-sync-periodic"
    private const val TRIGGER = "call-sync-trigger"
    private const val NOW = "call-sync-now"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun start(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
        armTrigger(context, append = false)
    }

    /** One-shot job that fires when the call log changes (JobScheduler content trigger). */
    fun armTrigger(context: Context, append: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .addContentUriTrigger(CallLog.Calls.CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(3))
            .setTriggerContentMaxDelay(Duration.ofSeconds(15))
            .build()
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).addTag(SyncWorker.TAG_TRIGGER).build()
        // Inside the running trigger job, append so the next trigger waits behind it instead of
        // cancelling it; from the app (start-up), keep whatever trigger is already armed.
        WorkManager.getInstance(context).enqueueUniqueWork(
            TRIGGER,
            if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network).build(),
        )
    }

    fun stop(context: Context) {
        val wm = WorkManager.getInstance(context)
        listOf(PERIODIC, TRIGGER, NOW).forEach { wm.cancelUniqueWork(it) }
    }
}

object Sync {
    private val lock = Mutex()

    /** First sync backfills this far; later ones re-read a day back so durations/late rows are caught. */
    private val BACKFILL = Duration.ofDays(3).toMillis()
    private val OVERLAP = Duration.ofDays(1).toMillis()
    private val PROMPT_WINDOW = Duration.ofMinutes(10).toMillis()
    private val RECORDING_WINDOW = Duration.ofDays(2).toMillis()

    suspend fun run(context: Context) = lock.withLock {
        val prefs = Prefs(context)
        if (!prefs.signedIn) return@withLock
        if (!granted(context, Manifest.permission.READ_CALL_LOG)) {
            prefs.lastError = "Call log permission is off."
            return@withLock
        }
        val now = System.currentTimeMillis()
        val since = if (prefs.lastCallMillis == 0L) now - BACKFILL else prefs.lastCallMillis - OVERLAP
        val rows = CallLogReader.read(context, since)
        val api = Api(prefs)
        val results = mutableListOf<Pair<CallRow, org.json.JSONObject>>()
        rows.chunked(200).forEach { chunk ->
            val byId = chunk.associateBy { it.id }
            val response = api.upload(chunk)
            for (i in 0 until response.length()) {
                val item = response.getJSONObject(i)
                byId[item.getString("device_call_id")]?.let { results += it to item }
            }
        }
        rows.maxOfOrNull { it.startedAt }?.let { if (it > prefs.lastCallMillis) prefs.lastCallMillis = it }
        prefs.lastSyncAt = now
        prefs.lastError = null

        if (prefs.uploadRecordings) uploadRecordings(context, api, prefs, results, now)
        if (prefs.postCallPopup) promptLatest(context, prefs, results, now)
    }

    /** Recordings go up only for calls matched to a school — never personal calls. */
    private fun uploadRecordings(context: Context, api: Api, prefs: Prefs, results: List<Pair<CallRow, org.json.JSONObject>>, now: Long) {
        if (!granted(context, audioPermission())) return
        for ((row, item) in results) {
            val id = item.getInt("id")
            if (item.isNull("school") || item.optBoolean("has_recording") || prefs.recordingDone(id)) continue
            if (now - row.startedAt > RECORDING_WINDOW) continue
            val found = Recordings.find(context, row) ?: continue
            val bytes = Recordings.read(context, found) ?: continue
            try {
                api.uploadRecording(id, found.name, bytes, found.mime)
                prefs.markRecordingDone(id)
            } catch (e: Api.Unauthorized) {
                throw e
            } catch (e: IOException) {
                // Try again next sync.
            }
        }
    }

    /** The newest call that just ended and still needs notes gets the pop-up (or a notification). */
    private fun promptLatest(context: Context, prefs: Prefs, results: List<Pair<CallRow, org.json.JSONObject>>, now: Long) {
        val (row, item) = results
            .filter { (row, item) -> item.getString("status") == "pending" && now - (row.startedAt + row.durationSec * 1000) < PROMPT_WINDOW }
            .maxByOrNull { it.first.startedAt } ?: return
        val id = item.getInt("id")
        if (prefs.wasPrompted(id)) return
        prefs.markPrompted(id)
        val intent = PostCallActivity.intent(context, id, row, item.optString("school_name"))
        if (Settings.canDrawOverlays(context)) {
            // "Display over other apps" lets us open the card straight away, like Runo/Truecaller.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Notifier.addNotes(context, id, row, item.optString("school_name"), intent)
        }
    }

    fun audioPermission() =
        if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
