package com.vikash.voicescribe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD1C8),
    onPrimary = Color(0xFF00332E),
    primaryContainer = Color(0xFF1D4E48),
    onPrimaryContainer = Color(0xFFB9F0E8),
    secondary = Color(0xFFF2B8A2),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF15191E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF20262C),
    onSurfaceVariant = Color(0xFFB8BEC6),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF00695F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0E8),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF9C4A2A),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFF4F6F8),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE6E9EC),
    onSurfaceVariant = Color(0xFF44484D),
)

@Composable
fun VoiceScribeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
