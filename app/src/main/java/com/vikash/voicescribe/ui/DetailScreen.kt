package com.vikash.voicescribe.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vikash.voicescribe.App
import com.vikash.voicescribe.data.Recording
import com.vikash.voicescribe.data.TranscriptStatus
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SHARE_FOOTER = "\n\n— Transcribed offline with VoiceScribe"

/** Thin state holder over MediaPlayer for local WAV/M4A playback. */
@Stable
private class AudioPlayer(private val path: String) {
    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var speed by mutableFloatStateOf(1f)
        private set

    private val mp = MediaPlayer()

    init {
        mp.setDataSource(path)
        mp.prepare()
        durationMs = mp.duration.toLong()
        mp.setOnCompletionListener {
            isPlaying = false
            positionMs = durationMs
        }
        // An IO/decoder error leaves MediaPlayer in a dead state; reopen the file
        // so the next play() works instead of the bar going permanently inert.
        mp.setOnErrorListener { player, _, _ ->
            isPlaying = false
            runCatching {
                player.reset()
                player.setDataSource(path)
                player.prepare()
                positionMs = 0
            }
            true
        }
    }

    fun toggle() = if (isPlaying) pause() else play()

    fun play() {
        // setPlaybackParams implicitly starts playback, so only touch it here
        mp.playbackParams = mp.playbackParams.setSpeed(speed)
        mp.start()
        isPlaying = true
    }

    fun pause() {
        mp.pause()
        isPlaying = false
    }

    fun seekTo(ms: Long) {
        mp.seekTo(ms.toInt().coerceIn(0, durationMs.toInt()))
        positionMs = ms.coerceIn(0, durationMs)
    }

    fun seekAndPlay(ms: Long) {
        seekTo(ms)
        if (!isPlaying) play()
    }

    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        if (isPlaying) mp.playbackParams = mp.playbackParams.setSpeed(speed)
    }

    fun tick() {
        if (isPlaying) positionMs = mp.currentPosition.toLong()
    }

    fun release() = mp.release()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(app: App, recordingId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val recordings by app.store.recordings.collectAsState()
    val rec = recordings.find { it.id == recordingId }
    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    if (rec == null) {
        onBack()
        return
    }

    // Recreated when the background transcode swaps the WAV for an M4A.
    val player = remember(rec.audioPath) {
        if (File(rec.audioPath).exists()) runCatching { AudioPlayer(rec.audioPath) }.getOrNull() else null
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    LaunchedEffect(player, player?.isPlaying) {
        while (player?.isPlaying == true) {
            player.tick()
            delay(200)
        }
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
                title = {
                    Text(
                        rec.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { renaming = true },
                    )
                },
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
                    remember(rec.createdAt) {
                        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(rec.createdAt))
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
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

            if (player != null) {
                PlayerBar(player)
            }

            when (rec.status) {
                TranscriptStatus.DONE -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Summary") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Transcript") })
                    }
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
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
                            TranscriptBody(rec, player, scrollState)
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

    if (renaming) {
        var draft by remember(rec.id) { mutableStateOf(rec.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        renaming = false
                        app.store.upsert(rec.copy(title = draft.trim(), titleEdited = true))
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recording?") },
            text = { Text("The audio, transcript, and summary will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    player?.release()
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
private fun PlayerBar(player: AudioPlayer) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = { player.toggle() }) {
                Icon(
                    if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (player.isPlaying) "Pause" else "Play",
                )
            }
            Slider(
                value = player.positionMs.toFloat(),
                onValueChange = { player.seekTo(it.toLong()) },
                valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${formatDuration(player.positionMs)} / ${formatDuration(player.durationMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { player.cycleSpeed() }) {
                    Text(
                        when (player.speed) {
                            1.5f -> "1.5×"
                            2f -> "2×"
                            else -> "1×"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptBody(rec: Recording, player: AudioPlayer?, scrollState: ScrollState) {
    if (rec.segments.isEmpty()) {
        // Pre-0.2 recordings have no timestamps — plain text fallback.
        Text(
            rec.transcript.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3,
        )
        return
    }

    // Row top offsets inside the scrollable column, filled in as rows lay out.
    val rowOffsets = remember(rec.segments) { mutableStateMapOf<Int, Int>() }
    val activeIndex = if (player != null && player.isPlaying) {
        rec.segments.indexOfFirst {
            player.positionMs >= it.t0Ms && player.positionMs < it.t1Ms.coerceAtLeast(it.t0Ms + 1)
        }
    } else -1

    // Keep the active segment ~1/3 from the top, but never yank the list
    // out from under a user who is scrolling themselves.
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0 || scrollState.isScrollInProgress) return@LaunchedEffect
        val rowTop = rowOffsets[activeIndex] ?: return@LaunchedEffect
        scrollState.animateScrollTo((rowTop - scrollState.viewportSize / 3).coerceAtLeast(0))
    }

    rec.segments.forEachIndexed { index, seg ->
        val active = index == activeIndex
        Row(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { rowOffsets[index] = it.positionInParent().y.toInt() }
                .background(
                    if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    RoundedCornerShape(8.dp),
                )
                .clickable(enabled = player != null) { player?.seekAndPlay(seg.t0Ms) }
                .padding(horizontal = 6.dp, vertical = 6.dp)
        ) {
            Text(
                formatDuration(seg.t0Ms),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(52.dp).padding(top = 3.dp),
            )
            Text(
                seg.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
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
