package com.abl.calls

import android.os.Build
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Blocking calls to the call-sync and phone-calls APIs (/api/sales/...) — run them off the main thread. */
class Api(private val prefs: Prefs) {

    open class ApiException(message: String, val code: Int) : IOException(message)

    /** The device token was revoked (unpaired from the portal) or the account deactivated. */
    class Unauthorized(message: String) : ApiException(message, 401)

    private val json = "application/json".toMediaType()

    private fun base() = prefs.baseUrl

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Device ${prefs.token ?: throw Unauthorized("Not signed in.")}")

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401) throw Unauthorized(errorMessage(body, "Signed out. Sign in again."))
            if (!response.isSuccessful) throw ApiException(errorMessage(body, "Server error ${response.code}"), response.code)
            return body
        }
    }

    private fun get(path: String): String = execute(authed(Request.Builder().url(base() + path)).get().build())

    private fun post(path: String, body: RequestBody, auth: Boolean = true, bearer: String? = null): String {
        var builder = Request.Builder().url(base() + path).post(body)
        builder = when {
            bearer != null -> builder.header("Authorization", "Bearer $bearer")
            auth -> authed(builder)
            else -> builder
        }
        return execute(builder.build())
    }

    // ------------------------------------------------------------ sign-in

    /** Employee ID + portal password → JWT (used once, only to pair this phone). */
    fun login(username: String, password: String): String {
        val body = JSONObject().put("username", username.trim()).put("password", password).toString().toRequestBody(json)
        return JSONObject(post("/auth/token/", body, auth = false)).getString("access")
    }

    /** Returns the device token, keeps the profile name and device id. */
    fun pair(access: String): String {
        val body = JSONObject()
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("app_version", BuildConfig.VERSION_NAME)
            .toString().toRequestBody(json)
        val data = JSONObject(post("/sales/call-sync/pair/", body, auth = false, bearer = access))
        prefs.deviceId = data.getJSONObject("device").getInt("id")
        prefs.userName = data.getJSONObject("profile").optString("name")
        return data.getString("token")
    }

    fun unpair() {
        if (prefs.deviceId != 0) post("/sales/call-sync/devices/${prefs.deviceId}/revoke/", "{}".toRequestBody(json))
    }

    // ------------------------------------------------------------ sync

    fun upload(rows: List<CallRow>): JSONArray {
        val calls = JSONArray()
        rows.forEach { calls.put(it.toJson()) }
        val body = JSONObject().put("calls", calls).put("app_version", BuildConfig.VERSION_NAME).toString().toRequestBody(json)
        return JSONObject(post("/sales/call-sync/upload/", body)).getJSONArray("calls")
    }

    fun uploadRecording(phoneCallId: Int, fileName: String, bytes: ByteArray, mime: String) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaType()))
            .build()
        post("/sales/phone-calls/$phoneCallId/recording/", body)
    }

    // ------------------------------------------------------------ post-call pop-up

    fun lookup(number: String): JSONObject = JSONObject(get("/sales/call-sync/lookup/?number=${enc(number)}"))

    fun searchSchools(q: String): JSONArray = JSONArray(get("/sales/call-sync/schools/?q=${enc(q)}"))

    fun logCall(phoneCallId: Int, payload: JSONObject): JSONObject =
        JSONObject(post("/sales/phone-calls/$phoneCallId/log/", payload.toString().toRequestBody(json)))

    fun markPersonal(phoneCallId: Int) {
        post("/sales/phone-calls/$phoneCallId/ignore/", "{}".toRequestBody(json))
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .build()

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

        /** First human-readable message out of a DRF error body. */
        fun errorMessage(body: String, fallback: String): String = try {
            val obj = JSONObject(body)
            val key = when {
                obj.has("detail") -> "detail"
                obj.has("non_field_errors") -> "non_field_errors"
                else -> obj.keys().asSequence().firstOrNull()
            }
            when (val value = key?.let { obj.get(it) }) {
                is String -> value
                is JSONArray -> value.optString(0, fallback)
                else -> fallback
            }
        } catch (e: Exception) {
            fallback
        }
    }
}
