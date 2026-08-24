package com.vikash.voicescribe.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vikash.voicescribe.App
import com.vikash.voicescribe.data.TranscriptStatus

private const val SHARE_FOOTER = "\n\n— Transcribed offline with VoiceScribe"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(app: App, recordingId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val recordings by app.store.recordings.collectAsState()
    val rec = recordings.find { it.id == recordingId }
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (rec == null) {
        onBack()
        return
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, rec.title)
            putExtra(Intent.EXTRA_TEXT, text + SHARE_FOOTER)
        }
        context.startActivity(Intent.createChooser(send, "Share"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(rec.title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { app.engine.enqueue(rec.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Re-transcribe")
                    }
                    if (rec.status == TranscriptStatus.DONE) {
                        IconButton(onClick = {
                            share(
                                if (tab == 0 && rec.summary.isNotEmpty())
                                    rec.summary.joinToString("\n") { "• $it" }
                                else rec.transcript.orEmpty()
                            )
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatDuration(rec.durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rec.language?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Language: ${it.uppercase()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (rec.status) {
                TranscriptStatus.DONE -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Summary") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Transcript") })
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        if (tab == 0) {
                            if (rec.summary.isEmpty()) {
                                Text("Nothing to summarize.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                rec.summary.forEach { bullet ->
                                    Row(Modifier.padding(bottom = 10.dp)) {
                                        Text("•  ", style = MaterialTheme.typography.bodyLarge)
                                        Text(bullet, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        } else {
                            Text(
                                rec.transcript.orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3,
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
                TranscriptStatus.TRANSCRIBING, TranscriptStatus.QUEUED -> CenteredStatus {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (rec.status == TranscriptStatus.TRANSCRIBING)
                            "Transcribing on this device…\nLonger recordings take a while."
                        else "Waiting in queue…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TranscriptStatus.NEEDS_MODEL -> CenteredStatus {
                    Text(
                        "Download the AI model to transcribe this recording.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TranscriptStatus.ERROR -> CenteredStatus {
                    Text(rec.error ?: "Transcription failed", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { app.engine.enqueue(rec.id) }) { Text("Retry") }
                }
                TranscriptStatus.NONE -> CenteredStatus {
                    TextButton(onClick = { app.engine.enqueue(rec.id) }) { Text("Transcribe") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("The audio, transcript, and summary will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.store.delete(rec.id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CenteredStatus(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        content()
    }
}
