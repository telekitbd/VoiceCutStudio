package com.voicecut.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF9D8CFF),
    onPrimary = Color(0xFF1A1033),
    background = Color(0xFF0E1014),
    surface = Color(0xFF171A21),
    surfaceVariant = Color(0xFF1F232C),
    onBackground = Color(0xFFE6E8EE),
    onSurface = Color(0xFFE6E8EE),
)

@Composable
fun VoiceCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}
