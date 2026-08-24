package com.vikash.voicescribe.transcribe

import android.util.Log
import com.vikash.voicescribe.audio.decodeWavToMono16k
import com.vikash.voicescribe.data.Recording
import com.vikash.voicescribe.data.RecordingStore
import com.vikash.voicescribe.data.TranscriptStatus
import com.vikash.voicescribe.model.ModelManager
import com.vikash.voicescribe.summarize.Summarizer
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "TranscriptionEngine"

/**
 * App-scoped serial transcription queue. Whisper contexts are single-threaded,
 * so one job runs at a time; the loaded context is reused across jobs and
 * reloaded only when the selected model file changes.
 */
class TranscriptionEngine(
    private val store: RecordingStore,
    private val models: ModelManager,
    scope: CoroutineScope,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var whisper: WhisperContext? = null
    private var loadedModelPath: String? = null

    init {
        scope.launch {
            for (id in queue) processOne(id)
        }
    }

    fun enqueue(id: String) {
        val rec = store.get(id) ?: return
        store.upsert(rec.copy(status = TranscriptStatus.QUEUED, error = null))
        queue.trySend(id)
    }

    /** Re-queue everything that recorded before a model was installed. */
    fun retryPending() {
        store.recordings.value
            .filter { it.status == TranscriptStatus.NEEDS_MODEL || it.status == TranscriptStatus.ERROR }
            .forEach { enqueue(it.id) }
    }

    private suspend fun processOne(id: String) {
        val rec = store.get(id) ?: return
        val modelFile = models.installedModelFile()
        if (modelFile == null) {
            store.upsert(rec.copy(status = TranscriptStatus.NEEDS_MODEL))
            return
        }
        try {
            store.upsert(rec.copy(status = TranscriptStatus.TRANSCRIBING))
            val ctx = loadContext(modelFile)
            val samples = decodeWavToMono16k(File(rec.wavPath))
            val text = ctx.transcribeData(samples, language = "auto", printTimestamp = false)
                .trim()
            val language = runCatching { ctx.detectedLanguage() }.getOrDefault("")
            val summary = Summarizer.summarize(text)
            store.upsert(
                rec.copy(
                    transcript = text.ifBlank { null },
                    summary = summary,
                    language = language.ifBlank { null },
                    status = if (text.isBlank()) TranscriptStatus.ERROR else TranscriptStatus.DONE,
                    error = if (text.isBlank()) "No speech detected" else null,
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Transcription failed for $id", t)
            store.upsert(rec.copy(status = TranscriptStatus.ERROR, error = t.message ?: "Transcription failed"))
        }
    }

    private suspend fun loadContext(modelFile: File): WhisperContext {
        val existing = whisper
        if (existing != null && loadedModelPath == modelFile.absolutePath) return existing
        existing?.release()
        whisper = null
        val ctx = WhisperContext.createContextFromFile(modelFile.absolutePath)
        whisper = ctx
        loadedModelPath = modelFile.absolutePath
        return ctx
    }
}
