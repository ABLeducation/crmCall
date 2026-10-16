@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.abl.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The card that pops up when a call ends: who it was (caller ID from the school master),
 * outcome, what was discussed and the next step. Saving makes it a SchoolCall on the
 * school's timeline; "Personal" leaves the number out from then on.
 */
class PostCallActivity : ComponentActivity() {

    data class Contact(val id: Int, val name: String, val designation: String, val hasMobile: Boolean)
    data class School(val id: Int, val name: String, val city: String, val contacts: List<Contact>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_ID, 0)
        Notifier.cancel(this, id)
        setContent {
            MaterialTheme {
                Card(Modifier.fillMaxWidth().padding(8.dp)) {
                    PostCallCard(
                        id = id,
                        number = intent.getStringExtra(EXTRA_NUMBER).orEmpty(),
                        nameOnPhone = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                        type = intent.getStringExtra(EXTRA_TYPE).orEmpty(),
                        duration = intent.getLongExtra(EXTRA_DURATION, 0),
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    @Composable
    private fun PostCallCard(id: Int, number: String, nameOnPhone: String, type: String, duration: Long, onDone: () -> Unit) {
        val api = remember { Api(Prefs(this)) }
        val scope = rememberCoroutineScope()
        var loading by remember { mutableStateOf(true) }
        var school by remember { mutableStateOf<School?>(null) }
        var matched by remember { mutableStateOf(false) }
        var dealLine by remember { mutableStateOf("") }
        var lastNotes by remember { mutableStateOf("") }
        val selected = remember { mutableStateListOf<Int>() }
        val connected = (type == "outgoing" || type == "incoming") && duration > 0
        var outcome by remember { mutableStateOf(if (connected) "connected" else "no_answer") }
        var notes by remember { mutableStateOf("") }
        var nextStep by remember { mutableStateOf("") }
        var followUp by remember { mutableStateOf<Long?>(null) }
        var rememberNumber by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<School>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(number) {
            try {
                val info = withContext(Dispatchers.IO) { api.lookup(number) }
                if (info.optBoolean("matched")) {
                    matched = true
                    val s = info.getJSONObject("school")
                    school = School(s.getInt("id"), s.getString("name"), s.optString("city"), contacts(info.optJSONArray("contacts")))
                    info.optJSONObject("contact")?.let { selected.add(it.getInt("id")) }
                    info.optJSONObject("deal")?.let { dealLine = "${it.optString("title")} · ${it.optString("stage_display")}" }
                    info.optJSONObject("last_call")?.let { lastNotes = it.optString("notes") }
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
        LaunchedEffect(query) {
            if (query.trim().length < 2) {
                results = emptyList()
                return@LaunchedEffect
            }
            delay(300)
            results = try {
                val arr = withContext(Dispatchers.IO) { api.searchSchools(query.trim()) }
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    School(s.getInt("id"), s.getString("name"), s.optString("city"), contacts(s.optJSONArray("contacts")))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        val current = school
        val rememberable = !matched && current != null && selected.size == 1 &&
            current.contacts.firstOrNull { it.id == selected[0] }?.hasMobile == false

        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(current?.name ?: nameOnPhone.ifEmpty { number }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "${type.replaceFirstChar { it.uppercase() }} · $number${if (duration > 0) " · ${formatDuration(duration)}" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            if (dealLine.isNotEmpty()) Text("Deal: $dealLine", style = MaterialTheme.typography.bodyMedium)
            if (lastNotes.isNotEmpty()) Text("Last call: $lastNotes", style = MaterialTheme.typography.bodySmall, maxLines = 2)

            if (!matched) {
                if (current == null) {
                    Text("Unknown number. Which school was this?", fontWeight = FontWeight.Medium)
                    OutlinedTextField(query, { query = it }, label = { Text("Search school") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    results.take(6).forEach { s ->
                        Text(
                            "${s.name} · ${s.city}",
                            Modifier.fillMaxWidth().clickable { school = s; selected.clear() }.padding(vertical = 8.dp),
                        )
                        HorizontalDivider()
                    }
                } else {
                    TextButton(onClick = { school = null; selected.clear() }) { Text("Change school") }
                }
            }

            if (current != null) {
                if (current.contacts.isNotEmpty()) {
                    Text("Spoke with", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        current.contacts.forEach { c ->
                            FilterChip(
                                selected = c.id in selected,
                                onClick = { if (c.id in selected) selected.remove(c.id) else selected.add(c.id) },
                                label = { Text(c.name) },
                            )
                        }
                    }
                }
                if (rememberable) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberNumber, { rememberNumber = it })
                        Text("Save this number to the contact", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Outcome", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OUTCOMES.forEach { (value, label) ->
                        FilterChip(selected = outcome == value, onClick = { outcome = value }, label = { Text(label) })
                    }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text("What was discussed *") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(nextStep, { nextStep = it }, label = { Text("Next step") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Follow up", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null to "None", 1L to "Tomorrow", 3L to "In 3 days", 7L to "Next week").forEach { (days, label) ->
                        FilterChip(selected = followUp == days, onClick = { followUp = days }, label = { Text(label) })
                    }
                }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDone, enabled = !busy) { Text("Later") }
                if (!matched) {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { api.markPersonal(id) }
                                onDone()
                            } catch (e: Exception) {
                                error = e.message
                                busy = false
                            }
                        }
                    }) { Text("Personal") }
                }
                Button(enabled = !busy && current != null && notes.isNotBlank(), onClick = {
                    val target = current ?: return@Button
                    busy = true
                    error = null
                    val payload = JSONObject()
                        .put("school", target.id)
                        .put("outcome", outcome)
                        .put("notes", notes.trim())
                        .put("next_step", nextStep.trim())
                        .put("contacts", JSONArray(selected.toList()))
                        .put("remember_number", rememberable && rememberNumber)
                    followUp?.let { payload.put("next_step_date", LocalDate.now().plusDays(it).toString()) }
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { api.logCall(id, payload) }
                            onDone()
                        } catch (e: Exception) {
                            error = e.message
                            busy = false
                        }
                    }
                }) { Text("Save") }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "phone_call_id"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_DURATION = "duration"

        private val OUTCOMES = listOf(
            "connected" to "Spoke to them",
            "no_answer" to "No answer",
            "busy" to "Busy / call back",
            "wrong_number" to "Wrong number",
        )

        fun intent(context: Context, phoneCallId: Int, row: CallRow, schoolName: String): Intent =
            Intent(context, PostCallActivity::class.java)
                .putExtra(EXTRA_ID, phoneCallId)
                .putExtra(EXTRA_NUMBER, row.number)
                .putExtra(EXTRA_NAME, schoolName.ifEmpty { row.name })
                .putExtra(EXTRA_TYPE, row.type)
                .putExtra(EXTRA_DURATION, row.durationSec)

        private fun contacts(arr: JSONArray?): List<Contact> =
            if (arr == null) emptyList() else (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Contact(c.getInt("id"), c.getString("name"), c.optString("designation"), c.optBoolean("has_mobile", true))
            }
    }
}
