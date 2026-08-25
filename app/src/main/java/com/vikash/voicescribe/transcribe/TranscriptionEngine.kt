package com.vikash.voicescribe.transcribe

import android.util.Log
import com.vikash.voicescribe.audio.decodeAudioToMono16k
import com.vikash.voicescribe.audio.transcodeWavToM4a
import com.vikash.voicescribe.data.Recording
import com.vikash.voicescribe.data.RecordingStore
import com.vikash.voicescribe.data.Segment
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
            val samples = decodeAudioToMono16k(File(rec.audioPath))
            val text = ctx.transcribeData(samples, language = "auto", printTimestamp = false)
                .trim()
            val segments = ctx.segments()
                .filter { it.text.isNotBlank() }
                .map { Segment(it.t0Ms, it.t1Ms, it.text) }
            val language = runCatching { ctx.detectedLanguage() }.getOrDefault("")
            val summary = Summarizer.summarize(text)
            val autoTitle = if (!rec.titleEdited) autoTitleFrom(text) else null
            store.upsert(
                rec.copy(
                    title = autoTitle ?: rec.title,
                    transcript = text.ifBlank { null },
                    segments = segments,
                    summary = summary,
                    language = language.ifBlank { null },
                    status = if (text.isBlank()) TranscriptStatus.ERROR else TranscriptStatus.DONE,
                    error = if (text.isBlank()) "No speech detected" else null,
                )
            )
            if (text.isNotBlank()) compressAudio(id)
        } catch (t: Throwable) {
            Log.e(TAG, "Transcription failed for $id", t)
            store.upsert(rec.copy(status = TranscriptStatus.ERROR, error = t.message ?: "Transcription failed"))
        }
    }

    /** First real words of the transcript as a title, or null if there's nothing usable. */
    private fun autoTitleFrom(text: String): String? {
        val cleaned = text
            .replace(Regex("\\[[^\\]]{1,20}\\]"), " ") // drop [Music]/[Bell]-style event tags
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.length < 8) return null
        val firstSentence = cleaned.split(Regex("(?<=[.!?।。？！])\\s")).first().trim()
        val base = firstSentence.ifBlank { cleaned }
        if (base.length <= 48) return base.trimEnd('.', ',', ';')
        val cut = base.take(48).substringBeforeLast(' ').trimEnd('.', ',', ';')
        return if (cut.length >= 8) "$cut…" else base.take(48)
    }

    /** Replaces the finished recording's WAV with M4A (~10× smaller). Keeps the WAV on failure. */
    private fun compressAudio(id: String) {
        val rec = store.get(id) ?: return
        val src = File(rec.audioPath)
        if (!src.exists() || src.extension.lowercase() != "wav") return
        val dst = store.m4aFileFor(rec.id)
        try {
            transcodeWavToM4a(src, dst)
            check(dst.length() > 0) { "empty output" }
            store.get(id)?.let { store.upsert(it.copy(audioPath = dst.absolutePath)) }
            src.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Transcode to M4A failed for $id, keeping WAV", t)
            dst.delete()
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
