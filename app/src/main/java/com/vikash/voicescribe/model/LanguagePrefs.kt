package com.vikash.voicescribe.model

import android.content.Context
import java.util.Locale

/**
 * The transcription language.
 *
 * Whisper can detect the language itself, but detection is least reliable on
 * exactly the small models this app defaults to, and a wrong guess doesn't
 * degrade the transcript — it destroys it. So the default is the phone's own
 * language, which is right far more often than a coin flip on a 30-second
 * window, and auto-detect stays available for people who record in several.
 */
data class TranscriptionLanguage(val code: String, val label: String)

class LanguagePrefs(context: Context) {

    companion object {
        const val AUTO = "auto"

        /**
         * Whisper handles 99 languages; this is the shortlist shown in the
         * picker — the app's largest markets plus the widely-recorded European
         * languages. Any device locale outside the list still works: it's added
         * to the top of the picker at runtime.
         */
        val COMMON = listOf(
            TranscriptionLanguage(AUTO, "Detect automatically"),
            TranscriptionLanguage("en", "English"),
            TranscriptionLanguage("hi", "Hindi"),
            TranscriptionLanguage("bn", "Bengali"),
            TranscriptionLanguage("ta", "Tamil"),
            TranscriptionLanguage("te", "Telugu"),
            TranscriptionLanguage("mr", "Marathi"),
            TranscriptionLanguage("ur", "Urdu"),
            TranscriptionLanguage("id", "Indonesian"),
            TranscriptionLanguage("vi", "Vietnamese"),
            TranscriptionLanguage("th", "Thai"),
            TranscriptionLanguage("tl", "Tagalog"),
            TranscriptionLanguage("es", "Spanish"),
            TranscriptionLanguage("pt", "Portuguese"),
            TranscriptionLanguage("fr", "French"),
            TranscriptionLanguage("de", "German"),
            TranscriptionLanguage("it", "Italian"),
            TranscriptionLanguage("ru", "Russian"),
            TranscriptionLanguage("ar", "Arabic"),
            TranscriptionLanguage("zh", "Chinese"),
            TranscriptionLanguage("ja", "Japanese"),
            TranscriptionLanguage("ko", "Korean"),
        )
    }

    private val prefs = context.getSharedPreferences("transcription", Context.MODE_PRIVATE)

    /** The phone's language if whisper knows it, otherwise auto-detect. */
    private fun deviceDefault(): String {
        val code = Locale.getDefault().language.lowercase()
        return if (COMMON.any { it.code == code }) code else AUTO
    }

    var selected: String
        get() = prefs.getString("language", null) ?: deviceDefault()
        set(value) { prefs.edit().putString("language", value).apply() }

    /** The picker list, with the device locale surfaced even if it's off the shortlist. */
    fun options(): List<TranscriptionLanguage> {
        val code = Locale.getDefault().language.lowercase()
        if (code.isEmpty() || COMMON.any { it.code == code }) return COMMON
        val name = Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
        return listOf(COMMON.first(), TranscriptionLanguage(code, name)) + COMMON.drop(1)
    }

    fun labelFor(code: String): String =
        options().find { it.code == code }?.label
            ?: Locale.forLanguageTag(code).displayLanguage.ifBlank { code }
}
