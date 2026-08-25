package com.vikash.voicescribe.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "AudioImporter"

/**
 * Copies a picked audio file (SAF uri) into the recordings store and returns the
 * new Recording, ready to be transcribed. Any format the framework can decode
 * works: the transcriber sniffs via MediaExtractor, WAV via the built-in parser.
 */
suspend fun importAudio(
    context: Context,
    store: RecordingStore,
    uri: Uri,
): Recording? = withContext(Dispatchers.IO) {
    try {
        val displayName = queryDisplayName(context, uri)
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf {
            it.length in 1..4 && it.all { c -> c.isLetterOrDigit() }
        } ?: extensionFromMime(context, uri)

        val id = store.newId()
        val target = File(store.wavFileFor(id).parentFile, "$id.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: return@withContext null
        if (target.length() == 0L) {
            target.delete()
            return@withContext null
        }

        val durationMs = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(target.absolutePath)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            } finally {
                r.release() // close() needs API 29; minSdk is 26
            }
        }.getOrNull() ?: 0L

        val title = displayName?.substringBeforeLast('.')?.trim()?.ifBlank { null }
        val rec = Recording(
            id = id,
            title = title ?: "Imported audio",
            // Imports keep their filename; auto-title stays off for them.
            titleEdited = title != null,
            createdAt = System.currentTimeMillis(),
            durationMs = durationMs,
            audioPath = target.absolutePath,
            status = TranscriptStatus.NONE,
        )
        store.upsert(rec)
        rec
    } catch (t: Throwable) {
        Log.e(TAG, "Import failed for $uri", t)
        null
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

private fun extensionFromMime(context: Context, uri: Uri): String =
    when (context.contentResolver.getType(uri)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/aac", "audio/aac-adts" -> "aac"
        "audio/amr", "audio/3gpp" -> "3gp"
        else -> "m4a"
    }
