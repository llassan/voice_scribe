package com.vikash.voicescribe.model

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which colour scheme to use, independent of the phone's own setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * App-wide preferences that the UI has to react to while it's on screen, so
 * these are exposed as flows rather than read once: the theme repaints
 * immediately when it changes, and the transcription engine reads the audio
 * setting at the moment it finishes a recording.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        _themeMode.value = mode
    }

    /**
     * Whether the audio is kept once a transcript exists. On by default — the
     * recording is what makes the transcript checkable, and the player and
     * tap-to-seek depend on it. Turning it off keeps transcripts only.
     */
    private val _saveAudio = MutableStateFlow(prefs.getBoolean("save_audio", true))
    val saveAudio: StateFlow<Boolean> = _saveAudio

    fun setSaveAudio(enabled: Boolean) {
        prefs.edit().putBoolean("save_audio", enabled).apply()
        _saveAudio.value = enabled
    }
}
