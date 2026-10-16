package com.abl.calls

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    /** Bumped on resume so permission states are re-read after the user returns from Settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val tick = resumes.intValue
                    var signedIn by remember { mutableStateOf(Prefs(this).signedIn) }
                    if (signedIn) StatusScreen(tick) { signedIn = false } else LoginScreen { signedIn = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    @Composable
    private fun LoginScreen(onSignedIn: () -> Unit) {
        val prefs = remember { Prefs(this) }
        val scope = rememberCoroutineScope()
        var server by remember { mutableStateOf(prefs.baseUrl) }
        var showServer by remember { mutableStateOf(false) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("ABL Calls", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Tracks the calls on this phone's SIM and syncs them to the ABL portal's Sales → Calls screen. Sign in with your portal login.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(username, { username = it }, label = { Text("Employee ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showServer) {
                OutlinedTextField(server, { server = it }, label = { Text("Server (API URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = { showServer = true }) { Text("Server: ${prefs.baseUrl}") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                prefs.baseUrl = server
                                val api = Api(prefs)
                                val access = api.login(username, password)
                                prefs.token = api.pair(access)
                            }
                            Scheduler.start(this@MainActivity)
                            Scheduler.syncNow(this@MainActivity)
                            onSignedIn()
                        } catch (e: Api.ApiException) {
                            error = if (e.code == 401) "Wrong Employee ID or password." else e.message
                        } catch (e: Exception) {
                            error = "Couldn't reach the server. Check your internet connection and the server address."
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
        }
    }

    @Composable
    private fun StatusScreen(tick: Int, onSignedOut: () -> Unit) {
        val prefs = remember { Prefs(this) }
        val scope = rememberCoroutineScope()
        var uploadRecordings by remember { mutableStateOf(prefs.uploadRecordings) }
        var popup by remember { mutableStateOf(prefs.postCallPopup) }
        var refresh by remember { mutableIntStateOf(0) }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh++
            Scheduler.syncNow(this)
        }
        // Re-read on every resume (back from Settings) and permission result.
        val granted = remember(tick, refresh) {
            mapOf(
                "callLog" to Sync.granted(this, Manifest.permission.READ_CALL_LOG),
                "notifications" to (Build.VERSION.SDK_INT < 33 || Sync.granted(this, Manifest.permission.POST_NOTIFICATIONS)),
                "audio" to Sync.granted(this, Sync.audioPermission()),
                "overlay" to Settings.canDrawOverlays(this),
                "battery" to getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            )
        }
        val callLog = granted.getValue("callLog")
        val notifications = granted.getValue("notifications")
        val audio = granted.getValue("audio")
        val overlay = granted.getValue("overlay")
        val battery = granted.getValue("battery")

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ABL Calls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Signed in as ${prefs.userName}", fontWeight = FontWeight.Medium)
                    Text(
                        if (prefs.lastSyncAt > 0) "Last synced ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(prefs.lastSyncAt))}"
                        else "Not synced yet",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    prefs.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Text("Set-up", style = MaterialTheme.typography.titleMedium)
            PermissionRow("Call log", "Required: reads your call history to sync it.", callLog) {
                permissions.launch(arrayOf(Manifest.permission.READ_CALL_LOG))
            }
            PermissionRow("Notifications", "Reminds you to add notes after a call.", notifications) {
                if (Build.VERSION.SDK_INT >= 33) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            PermissionRow("Display over other apps", "Opens the notes card as soon as a call ends.", overlay) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            PermissionRow("Not battery-restricted", "Keeps syncing when the app is closed.", battery) {
                @Suppress("BatteryLife")
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            PermissionRow("Call recordings", "Uploads recordings your phone's dialer saves, for school calls only.", audio) {
                permissions.launch(arrayOf(Sync.audioPermission()))
            }

            Text("Options", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Pop-up after each call", popup) { popup = it; prefs.postCallPopup = it }
            ToggleRow("Upload call recordings", uploadRecordings) { uploadRecordings = it; prefs.uploadRecordings = it }
            Text(
                "Personal calls: mark an unknown number as Personal once and its calls are left out from then on. " +
                    "Recordings are only uploaded for calls with schools.",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Scheduler.syncNow(this@MainActivity) }) { Text("Sync now") }
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { Api(prefs).unpair() } }
                        Scheduler.stop(this@MainActivity)
                        prefs.signOut()
                        onSignedOut()
                    }
                }) { Text("Sign out") }
            }
        }
    }

    @Composable
    private fun PermissionRow(title: String, detail: String, ok: Boolean, onFix: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            if (ok) Text("On", color = Color(0xFF1BAF7A), fontWeight = FontWeight.Medium)
            else OutlinedButton(onClick = onFix) { Text("Allow") }
        }
    }

    @Composable
    private fun ToggleRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}
