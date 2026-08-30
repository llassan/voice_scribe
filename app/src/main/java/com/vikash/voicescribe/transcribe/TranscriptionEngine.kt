package com.vikash.voicescribe.transcribe

import android.content.Context
import android.util.Log
import com.vikash.voicescribe.audio.decodeAudioToMono16k
import com.vikash.voicescribe.audio.transcodeWavToM4a
import com.vikash.voicescribe.data.Recording
import com.vikash.voicescribe.data.RecordingStore
import com.vikash.voicescribe.data.Segment
import com.vikash.voicescribe.data.TranscriptStatus
import com.vikash.voicescribe.model.LanguagePrefs
import com.vikash.voicescribe.model.ModelManager
import com.vikash.voicescribe.model.ModelSource
import com.vikash.voicescribe.service.TranscribeService
import com.vikash.voicescribe.summarize.Summarizer
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "TranscriptionEngine"

/**
 * App-scoped serial transcription queue. Whisper contexts are single-threaded,
 * so one job runs at a time; the loaded context is reused across jobs and
 * reloaded only when the selected model changes.
 */
class TranscriptionEngine(
    private val context: Context,
    private val store: RecordingStore,
    private val models: ModelManager,
    private val languages: LanguagePrefs,
    scope: CoroutineScope,
) {
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)
    private var whisper: WhisperContext? = null
    private var loadedModelKey: String? = null

    init {
        scope.launch {
            recoverInterrupted()
            for (id in queue) {
                try {
                    processOne(id)
                } finally {
                    // Drop the foreground anchor once the queue drains.
                    if (pending.decrementAndGet() == 0) TranscribeService.stop(context)
                }
            }
        }
    }

    fun enqueue(id: String) {
        val rec = store.get(id) ?: return
        store.upsert(rec.copy(status = TranscriptStatus.QUEUED, error = null))
        // Foreground service keeps long transcriptions alive when the app is backgrounded.
        if (pending.incrementAndGet() == 1) {
            runCatching { TranscribeService.start(context) }
        }
        queue.trySend(id)
    }

    /**
     * Nothing can be mid-flight at process start, so anything the store still lists
     * as pending was orphaned by a kill (low-memory deaths are common on the cheap
     * phones this app targets). Re-queue it, or fail it outright if its audio is gone.
     */
    private fun recoverInterrupted() {
        store.recordings.value
            .filter { it.status == TranscriptStatus.QUEUED || it.status == TranscriptStatus.TRANSCRIBING }
            .forEach { rec ->
                if (File(rec.audioPath).exists()) {
                    Log.i(TAG, "Recovering interrupted transcription ${rec.id}")
                    enqueue(rec.id)
                } else {
                    Log.w(TAG, "Audio missing for interrupted ${rec.id}, marking error")
                    store.upsert(
                        rec.copy(status = TranscriptStatus.ERROR, error = "Recording was interrupted")
                    )
                }
            }
    }

    /** Re-queue everything that recorded before a model was installed. */
    fun retryPending() {
        store.recordings.value
            .filter { it.status == TranscriptStatus.NEEDS_MODEL || it.status == TranscriptStatus.ERROR }
            .forEach { enqueue(it.id) }
    }

    private suspend fun processOne(id: String) {
        val rec = store.get(id) ?: return
        val model = models.installedModelSource()
        if (model == null) {
            store.upsert(rec.copy(status = TranscriptStatus.NEEDS_MODEL))
            return
        }
        try {
            store.upsert(rec.copy(status = TranscriptStatus.TRANSCRIBING))
            val ctx = loadContext(model)
            val samples = decodeAudioToMono16k(File(rec.audioPath))
            val diarize = model.fileName.contains("tdrz")
            // .en models only ever decode English; multilingual models use the
            // chosen language, which defaults to the phone's own rather than
            // auto-detect (see LanguagePrefs).
            val forcedLanguage =
                if (model.fileName.contains(".en")) "en" else languages.selected
            val text = ctx.transcribeData(
                samples, language = forcedLanguage, printTimestamp = false,
                enableDiarization = diarize, useContext = true,
            ).trim()
            val raw = ctx.segments().filter { it.text.isNotBlank() }
            // tinydiarize marks turn boundaries, not voice identity, so alternate
            // between two speakers — correct for the dominant 1:1 meeting/interview
            // case, and far less misleading than "Speaker 7" in a dialog.
            val anyTurns = diarize && raw.any { it.speakerTurnNext }
            var turnCount = 0
            val segments = raw.map { s ->
                val seg = Segment(
                    s.t0Ms, s.t1Ms, s.text,
                    speaker = if (anyTurns) turnCount % 2 else null,
                )
                if (s.speakerTurnNext) turnCount++
                seg
            }
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

    private suspend fun loadContext(model: ModelSource): WhisperContext {
        val existing = whisper
        if (existing != null && loadedModelKey == model.key) return existing
        existing?.release()
        whisper = null
        // Bundled models are read straight out of the APK — no copy to files/.
        val ctx = when (model) {
            is ModelSource.Bundled ->
                WhisperContext.createContextFromAsset(context.assets, model.assetPath)
            is ModelSource.Downloaded ->
                WhisperContext.createContextFromFile(model.file.absolutePath)
        }
        whisper = ctx
        loadedModelKey = model.key
        return ctx
    }
}
