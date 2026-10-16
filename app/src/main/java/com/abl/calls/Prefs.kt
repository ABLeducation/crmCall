package com.abl.calls

import android.content.Context

/** App-private settings. The device token is the only credential kept; the portal password never is. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("abl_calls", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", null) ?: BuildConfig.DEFAULT_BASE_URL
        set(value) = sp.edit().putString("base_url", value.trim().trimEnd('/')).apply()

    var token: String?
        get() = sp.getString("token", null)
        set(value) = sp.edit().putString("token", value).apply()

    var deviceId: Int
        get() = sp.getInt("device_id", 0)
        set(value) = sp.edit().putInt("device_id", value).apply()

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(value) = sp.edit().putString("user_name", value).apply()

    /** Call-log DATE (ms) of the newest call uploaded so far. */
    var lastCallMillis: Long
        get() = sp.getLong("last_call_ms", 0L)
        set(value) = sp.edit().putLong("last_call_ms", value).apply()

    /** Wall-clock time of the last successful sync. */
    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0L)
        set(value) = sp.edit().putLong("last_sync_at", value).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(value) = sp.edit().putString("last_error", value).apply()

    var uploadRecordings: Boolean
        get() = sp.getBoolean("upload_recordings", true)
        set(value) = sp.edit().putBoolean("upload_recordings", value).apply()

    var postCallPopup: Boolean
        get() = sp.getBoolean("post_call_popup", true)
        set(value) = sp.edit().putBoolean("post_call_popup", value).apply()

    /** Server PhoneCall ids already prompted for notes / with a recording uploaded (bounded). */
    fun wasPrompted(id: Int) = id.toString() in idSet("prompted")
    fun markPrompted(id: Int) = addId("prompted", id)
    fun recordingDone(id: Int) = id.toString() in idSet("recordings")
    fun markRecordingDone(id: Int) = addId("recordings", id)

    private fun idSet(key: String): List<String> =
        (sp.getString(key, "") ?: "").split(',').filter { it.isNotEmpty() }

    private fun addId(key: String, id: Int) {
        val ids = (idSet(key) + id.toString()).distinct().takeLast(500)
        sp.edit().putString(key, ids.joinToString(",")).apply()
    }

    fun signOut() {
        sp.edit().remove("token").remove("device_id").remove("user_name").remove("last_error").apply()
    }

    val signedIn: Boolean get() = token != null
}
