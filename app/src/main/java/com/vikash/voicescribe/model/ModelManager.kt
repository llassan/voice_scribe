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
    /** Full download URL when the model isn't hosted in the default repo. */
    val url: String? = null,
    /**
     * Path inside the APK's assets when the model ships with the app. Bundled
     * models need no download, so a fresh install transcribes on first open.
     */
    val assetPath: String? = null,
    /**
     * Kept out of the picker. Tiny is a rescue model, not a choice: it exists
     * only for phones that cannot load the bundled default, where something
     * inaccurate beats nothing at all. It appears in the list once installed so
     * a phone that fell back to it can still see and manage it.
     */
    val hidden: Boolean = false,
)

/**
 * A model that's ready to load — either bundled in the APK or downloaded to
 * files/. whisper.cpp reads both, so nothing is ever unpacked to disk.
 */
sealed class ModelSource {
    /** File name, used to tell the .en and tdrz variants apart at transcribe time. */
    abstract val fileName: String
    /** Stable identity, so a loaded context is reused until the model changes. */
    abstract val key: String

    data class Bundled(val assetPath: String) : ModelSource() {
        override val fileName get() = assetPath.substringAfterLast('/')
        override val key get() = "asset:$assetPath"
    }

    data class Downloaded(val file: File) : ModelSource() {
        override val fileName get() = file.name
        override val key get() = file.absolutePath
    }
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val modelId: String, val progress: Float) : DownloadState()
    data class Failed(val modelId: String, val message: String) : DownloadState()
}

/**
 * The Tiny model ships inside the APK so the app works the moment it's
 * installed; the larger models stay post-install downloads to keep that install
 * small. Quantized ggml builds from the official whisper.cpp repo.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

        val CATALOG = listOf(
            WhisperModel(
                "base-q5_1", "Balanced (Base)", "ggml-base-q5_1.bin", 57,
                "Good accuracy, quick on most phones. 99 languages.",
                // Ships inside the APK — see scripts/fetch-whisper.sh. Costs ~57 MB
                // of install size and buys a first run that works offline, with no
                // download and no call to Hugging Face. Tiny was bundled before
                // 1.3.0; it was too inaccurate to be the impression the app makes.
                assetPath = "models/ggml-base-q5_1.bin",
            ),
            WhisperModel(
                "tiny-q5_1", "Fast (Tiny)", "ggml-tiny-q5_1.bin", 31,
                "A small rescue model for phones that can't run Balanced. Less " +
                    "accurate, but it works where Balanced won't. 99 languages.",
                hidden = true,
            ),
            WhisperModel(
                "small-q5_1", "Accurate (Small)", "ggml-small-q5_1.bin", 181,
                "Best accuracy. Needs a recent phone with 6 GB+ RAM."
            ),
            WhisperModel(
                "small-en-tdrz", "Speakers (Small)", "ggml-small.en-tdrz.bin", 465,
                "Labels who's speaking in meetings and interviews. English only. Needs 6 GB+ RAM.",
                diarize = true,
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

    fun isInstalled(model: WhisperModel): Boolean =
        model.assetPath != null || fileFor(model).exists()

    /** Bundled models can't be removed — they live in the APK. */
    fun isBundled(model: WhisperModel): Boolean = model.assetPath != null

    fun sourceFor(model: WhisperModel): ModelSource? = when {
        // A downloaded copy wins: it's byte-identical, and it's what a build
        // missing its assets falls back to.
        fileFor(model).exists() -> ModelSource.Downloaded(fileFor(model))
        model.assetPath != null -> ModelSource.Bundled(model.assetPath)
        else -> null
    }

    fun installedModelSource(): ModelSource? {
        CATALOG.find { it.id == selectedId }?.let { model -> sourceFor(model)?.let { return it } }
        // fall back to any installed model
        return CATALOG.firstNotNullOfOrNull { sourceFor(it) }
    }

    /**
     * Base, always: it's bundled, so it transcribes on first open with no
     * download. Everything else is an opt-in choice rather than a cold-start tax.
     */
    fun recommended(): WhisperModel = CATALOG[0]

    /**
     * The model worth suggesting once someone records something long enough that
     * accuracy starts to matter — a lecture or a meeting rather than a memo.
     * Null on low-RAM phones, where Tiny is the right ceiling anyway.
     */
    fun upgradeSuggestion(): WhisperModel? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalGB = info.totalMem / (1024.0 * 1024 * 1024)
        // Base is already bundled, so the only step up is Small — a 181 MB
        // download that needs the RAM to run. Never suggested below 6 GB.
        return if (totalGB < 6.5) null else CATALOG.first { it.id == "small-q5_1" }
    }

    /** The rescue model used when the bundled default can't be loaded. */
    val fallback: WhisperModel get() = CATALOG.first { it.id == "tiny-q5_1" }

    /** Models to show in the picker: hidden ones only once they're installed. */
    fun visibleModels(): List<WhisperModel> = CATALOG.filter { !it.hidden || isInstalled(it) }

    /** One-shot: the accuracy upgrade is offered once and never nags again. */
    var upgradeOffered: Boolean
        get() = prefs.getBoolean("upgrade_offered", false)
        set(value) { prefs.edit().putBoolean("upgrade_offered", value).apply() }

    private fun scanInstalled(): Set<String> =
        CATALOG.filter { isInstalled(it) }.map { it.id }.toSet()

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
        // Bundled models live in the APK; there's nothing on disk to reclaim.
        if (model.assetPath != null) return
        fileFor(model).delete()
        _installedIds.value = scanInstalled()
    }
}
