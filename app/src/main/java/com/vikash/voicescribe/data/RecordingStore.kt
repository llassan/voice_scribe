package com.vikash.voicescribe.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class TranscriptStatus { NONE, NEEDS_MODEL, QUEUED, TRANSCRIBING, DONE, ERROR }

data class Segment(val t0Ms: Long, val t1Ms: Long, val text: String)

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
        _recordings.value = dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

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
        File(dir, "$id.json").delete()
        wavFileFor(id).delete()
        m4aFileFor(id).delete()
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
                Segment(s.getLong("t0"), s.getLong("t1"), s.getString("text"))
            }
        } ?: emptyList()
        // A recording persisted mid-transcription means the process died: mark it retryable.
        val status = TranscriptStatus.valueOf(o.optString("status", "NONE")).let {
            if (it == TranscriptStatus.QUEUED || it == TranscriptStatus.TRANSCRIBING) TranscriptStatus.NEEDS_MODEL else it
        }
        return Recording(
            id = o.getString("id"),
            title = o.getString("title"),
            titleEdited = o.optBoolean("titleEdited", false),
            createdAt = o.getLong("createdAt"),
            durationMs = o.getLong("durationMs"),
            // "wavPath" is the pre-0.2 key
            audioPath = o.optString("audioPath").ifBlank { o.getString("wavPath") },
            transcript = if (o.isNull("transcript")) null else o.getString("transcript"),
            segments = segments,
            summary = summary,
            language = if (o.isNull("language")) null else o.getString("language"),
            status = status,
            error = if (o.isNull("error")) null else o.getString("error"),
        )
    }
}
