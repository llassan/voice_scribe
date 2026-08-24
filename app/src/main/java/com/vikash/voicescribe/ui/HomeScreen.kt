package com.vikash.voicescribe.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.vikash.voicescribe.App
import com.vikash.voicescribe.data.Recording
import com.vikash.voicescribe.data.TranscriptStatus
import com.vikash.voicescribe.service.RecorderService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    app: App,
    onOpenRecording: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    val context = LocalContext.current
    val recordings by app.store.recordings.collectAsState()
    val session by RecorderService.session.collectAsState()
    val installed by app.models.installedIds.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            RecorderService.start(context)
        }
    }

    fun startRecording() {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            RecorderService.start(context)
        } else {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoiceScribe", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenModels) {
                        Icon(Icons.Filled.Tune, contentDescription = "Models")
                    }
                }
            )
        },
        bottomBar = {
            RecordBar(
                isRecording = session != null,
                elapsedMs = session?.elapsedMs ?: 0L,
                amplitude = session?.amplitude ?: 0f,
                onStart = { startRecording() },
                onStop = { RecorderService.stop(context) },
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (installed.isEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { onOpenModels() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Enable offline transcription",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "Download the AI model (~${app.models.recommended().sizeMB} MB, one time). " +
                                "Everything runs on this phone — nothing is uploaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            if (recordings.isEmpty() && session == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.GraphicEq, contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Tap the mic to record a lecture,\nmeeting, or voice memo",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recordings, key = { it.id }) { rec ->
                        RecordingCard(rec, onClick = { onOpenRecording(rec.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(rec: Recording, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick)
            .animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    rec.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatDuration(rec.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            when (rec.status) {
                TranscriptStatus.DONE -> Text(
                    rec.summary.firstOrNull() ?: rec.transcript.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TranscriptStatus.TRANSCRIBING, TranscriptStatus.QUEUED -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (rec.status == TranscriptStatus.TRANSCRIBING) "Transcribing…" else "Queued…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TranscriptStatus.NEEDS_MODEL -> StatusChip("Waiting for model download")
                TranscriptStatus.ERROR -> StatusChip(rec.error ?: "Failed", isError = true)
                TranscriptStatus.NONE -> StatusChip("Not transcribed")
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RecordBar(
    isRecording: Boolean,
    elapsedMs: Long,
    amplitude: Float,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isRecording) {
            Text(
                formatDuration(elapsedMs),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
        }
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 1f,
            targetValue = if (isRecording) 1f + (0.12f + amplitude * 0.25f) else 1f,
            animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "pulseScale",
        )
        Box(
            modifier = Modifier
                .size(76.dp)
                .scale(if (isRecording) pulse else 1f)
                .background(
                    if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    CircleShape,
                )
                .clickable { if (isRecording) onStop() else onStart() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                tint = if (isRecording) MaterialTheme.colorScheme.onError
                else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(34.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (isRecording) "Recording — tap to stop" else "Tap to record",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
