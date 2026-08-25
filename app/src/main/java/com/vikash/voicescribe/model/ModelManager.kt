package com.vikash.voicescribe.model

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class WhisperModel(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeMB: Int,
    val description: String,
    /** tinydiarize model: emits speaker-turn markers during transcription. */
    val diarize: Boolean = false,
    /** Requires the one-time Pro unlock to download. */
    val pro: Boolean = false,
    /** Full download URL when the model isn't hosted in the default repo. */
    val url: String? = null,
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val modelId: String, val progress: Float) : DownloadState()
    data class Failed(val modelId: String, val message: String) : DownloadState()
}

/**
 * Whisper models ship as a post-install download (keeps the APK tiny for
 * install conversion). Quantized ggml builds from the official whisper.cpp repo.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        val CATALOG = listOf(
            WhisperModel(
                "tiny-q5_1", "Fast (Tiny)", "ggml-tiny-q5_1.bin", 32,
                "Fastest, lightest. Good for quick memos on any device. 99 languages."
            ),
            WhisperModel(
                "base-q5_1", "Balanced (Base)", "ggml-base-q5_1.bin", 60,
                "Better accuracy, still quick on mid-range phones. 99 languages."
            ),
            WhisperModel(
                "small-q5_1", "Accurate (Small)", "ggml-small-q5_1.bin", 190,
                "Best accuracy. Needs a recent phone with 6 GB+ RAM."
            ),
            WhisperModel(
                "small-en-tdrz", "Speakers (Small)", "ggml-small.en-tdrz.bin", 465,
                "Labels who's speaking in meetings and interviews. English only. Needs 6 GB+ RAM.",
                diarize = true,
                pro = true,
                // tinydiarize models live in the author's repo (whisper.cpp's own
                // download script points there too)
                url = "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin",
            ),
        )
    }

    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    private val _installedIds = MutableStateFlow(scanInstalled())
    val installedIds: StateFlow<Set<String>> = _installedIds

    var selectedId: String
        get() = prefs.getString("selected", null) ?: recommended().id
        set(value) { prefs.edit().putString("selected", value).apply() }

    fun fileFor(model: WhisperModel): File = File(modelsDir, model.fileName)

    fun installedModelFile(): File? {
        val selected = CATALOG.find { it.id == selectedId }?.let { fileFor(it) }
        if (selected?.exists() == true) return selected
        // fall back to any installed model
        return CATALOG.map { fileFor(it) }.firstOrNull { it.exists() }
    }

    /** Device-tier detection: low-RAM phones get the tiny model by default. */
    fun recommended(): WhisperModel {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGB = info.totalMem / (1024.0 * 1024 * 1024)
        return when {
            totalGB < 3.5 -> CATALOG[0]
            totalGB < 6.5 -> CATALOG[1]
            else -> CATALOG[1] // small stays opt-in: 190 MB download
        }
    }

    private fun scanInstalled(): Set<String> =
        CATALOG.filter { fileFor(it).exists() }.map { it.id }.toSet()

    suspend fun download(model: WhisperModel): Boolean = withContext(Dispatchers.IO) {
        val target = fileFor(model)
        if (target.exists()) return@withContext true
        val part = File(modelsDir, model.fileName + ".part")
        _downloadState.value = DownloadState.Downloading(model.id, 0f)
        try {
            val conn = URL(model.url ?: (BASE_URL + model.fileName)).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.connect()
            check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (model.sizeMB * 1024L * 1024L)
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _downloadState.value =
                            DownloadState.Downloading(model.id, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            check(part.length() > 1024 * 1024) { "Download truncated" }
            check(part.renameTo(target)) { "Couldn't finalize model file" }
            selectedId = model.id
            _installedIds.value = scanInstalled()
            _downloadState.value = DownloadState.Idle
            true
        } catch (t: Throwable) {
            part.delete()
            _downloadState.value = DownloadState.Failed(model.id, t.message ?: "Download failed")
            false
        }
    }

    fun deleteModel(model: WhisperModel) {
        fileFor(model).delete()
        _installedIds.value = scanInstalled()
    }
}
