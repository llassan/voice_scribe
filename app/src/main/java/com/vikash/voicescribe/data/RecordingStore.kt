package com.vikash.voicescribe.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private const val TAG = "RecordingStore"

enum class TranscriptStatus { NONE, NEEDS_MODEL, QUEUED, TRANSCRIBING, DONE, ERROR }

data class Segment(
    val t0Ms: Long,
    val t1Ms: Long,
    val text: String,
    /** 0-based speaker index from diarization; null when no speaker data. */
    val speaker: Int? = null,
)

data class Recording(
    val id: String,
    val title: String,
    /** True once the user renames it; blocks auto-titling from the transcript. */
    val titleEdited: Boolean = false,
    val createdAt: Long,
    val durationMs: Long,
    /** WAV right after recording; replaced by M4A once background transcode finishes. */
    val audioPath: String,
    val transcript: String? = null,
    val segments: List<Segment> = emptyList(),
    val summary: List<String> = emptyList(),
    val language: String? = null,
    val status: TranscriptStatus = TranscriptStatus.NONE,
    val error: String? = null,
)

/**
 * File-backed store: recordings/<id>.wav + <id>.json under filesDir.
 * All mutations are synchronized and mirrored into [recordings] for the UI.
 */
class RecordingStore(context: Context) {
    private val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    private val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val recordings: StateFlow<List<Recording>> = _recordings

    init {
        _recordings.value = load()
    }

    /**
     * Builds the list from disk, and never loses audio that is still there.
     *
     * Previously a recording whose .json failed to parse was dropped silently —
     * no log, no trace in the UI — while its audio sat on disk untouched. To the
     * user that is indistinguishable from "all my recordings disappeared", and
     * nothing in the app could tell them otherwise. Now a parse failure is logged
     * with the file that caused it, and any audio file without a readable .json
     * is recovered as a playable recording instead of becoming invisible.
     */
    private fun load(): List<Recording> {
        val files = dir.listFiles() ?: return emptyList()

        val parsed = files.filter { it.extension == "json" }.mapNotNull { f ->
            runCatching { fromJson(f.readText()) }
                .onFailure { Log.w(TAG, "Unreadable recording index ${f.name}, will try to recover its audio", it) }
                .getOrNull()
        }

        val known = parsed.mapTo(mutableSetOf()) { it.id }
        val recovered = files
            .filter { it.extension != "json" && it.nameWithoutExtension !in known }
            .map { audio ->
                Log.w(TAG, "Recovering orphaned audio ${audio.name}")
                Recording(
                    id = audio.nameWithoutExtension,
                    title = "Recovered — ${dateFormat.format(Date(audio.lastModified()))}",
                    createdAt = audio.lastModified(),
                    durationMs = durationOf(audio),
                    audioPath = audio.absolutePath,
                )
            }

        // Persist the rebuilt entries so the recovery is permanent and they behave
        // like any other recording from here on (rename, transcribe, delete).
        recovered.forEach { rec ->
            runCatching { File(dir, "${rec.id}.json").writeText(toJson(rec)) }
                .onFailure { Log.w(TAG, "Could not persist recovered ${rec.id}", it) }
        }

        return (parsed + recovered).sortedByDescending { it.createdAt }
    }

    /** Best-effort; a recovered file with an unreadable header still lists, at 0:00. */
    private fun durationOf(f: File): Long = runCatching {
        // Not use{}: MediaMetadataRetriever only became AutoCloseable at API 29
        // and this app supports 26.
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(f.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally {
            mmr.release()
        }
    }.getOrDefault(0L)

    private val dateFormat get() = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    fun newId(): String = UUID.randomUUID().toString()

    fun wavFileFor(id: String): File = File(dir, "$id.wav")

    fun m4aFileFor(id: String): File = File(dir, "$id.m4a")

    fun get(id: String): Recording? = _recordings.value.find { it.id == id }

    @Synchronized
    fun upsert(recording: Recording) {
        File(dir, "${recording.id}.json").writeText(toJson(recording))
        _recordings.value = (_recordings.value.filter { it.id != recording.id } + recording)
            .sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun delete(id: String) {
        // Covers .json, .wav, .m4a, and any imported extension (.mp3, .ogg, …)
        dir.listFiles { f -> f.name.startsWith("$id.") }?.forEach { it.delete() }
        _recordings.value = _recordings.value.filter { it.id != id }
    }

    private fun toJson(r: Recording): String = JSONObject().apply {
        put("id", r.id)
        put("title", r.title)
        put("titleEdited", r.titleEdited)
        put("createdAt", r.createdAt)
        put("durationMs", r.durationMs)
        put("audioPath", r.audioPath)
        put("transcript", r.transcript ?: JSONObject.NULL)
        put("segments", JSONArray(r.segments.map { s ->
            JSONObject().apply {
                put("t0", s.t0Ms)
                put("t1", s.t1Ms)
                put("text", s.text)
                put("speaker", s.speaker ?: JSONObject.NULL)
            }
        }))
        put("summary", JSONArray(r.summary))
        put("language", r.language ?: JSONObject.NULL)
        put("status", r.status.name)
        put("error", r.error ?: JSONObject.NULL)
    }.toString()

    private fun fromJson(text: String): Recording {
        val o = JSONObject(text)
        val summary = o.optJSONArray("summary")?.let { arr ->
            List(arr.length()) { arr.getString(it) }
        } ?: emptyList()
        val segments = o.optJSONArray("segments")?.let { arr ->
            List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                Segment(
                    s.getLong("t0"), s.getLong("t1"), s.getString("text"),
                    speaker = if (s.isNull("speaker")) null else s.getInt("speaker"),
                )
            }
        } ?: emptyList()
        // A recording persisted mid-transcription means the process died before it
        // finished. No job survives process death, so it is pending again, not
        // model-blocked; TranscriptionEngine's startup sweep re-enqueues these.
        val status = TranscriptStatus.valueOf(o.optString("status", "NONE")).let {
            if (it == TranscriptStatus.TRANSCRIBING) TranscriptStatus.QUEUED else it
        }
        return Recording(
            id = o.getString("id"),
            title = o.getString("title"),
            titleEdited = o.optBoolean("titleEdited", false),
            createdAt = o.getLong("createdAt"),
            durationMs = o.getLong("durationMs"),
            // "wavPath" is the pre-0.2 key
            // "wavPath" is the pre-1.1 field name. optString, not getString: a
            // recording whose audio was discarded has an empty audioPath and no
            // wavPath, and getString would throw — silently dropping the whole
            // recording, transcript and all, from the list.
            audioPath = o.optString("audioPath").ifBlank { o.optString("wavPath") },
            transcript = if (o.isNull("transcript")) null else o.getString("transcript"),
            segments = segments,
            summary = summary,
            language = if (o.isNull("language")) null else o.getString("language"),
            status = status,
            error = if (o.isNull("error")) null else o.getString("error"),
        )
    }
}
