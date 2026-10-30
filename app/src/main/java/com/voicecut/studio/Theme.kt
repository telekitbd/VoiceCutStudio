package com.voicecut.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E2FF),
    onPrimaryContainer = Color(0xFF1B1668),
    secondary = Color(0xFF0F8F7F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7F3EE),
    onSecondaryContainer = Color(0xFF053B34),
    background = Color(0xFFF5F6FB),
    onBackground = Color(0xFF14161F),
    surface = Color.White,
    onSurface = Color(0xFF14161F),
    surfaceVariant = Color(0xFFEBEDF6),
    onSurfaceVariant = Color(0xFF5A5F72),
    outline = Color(0xFFA6AABD),
    outlineVariant = Color(0xFFDDE0EC),
    errorContainer = Color(0xFFFFE1DE),
    onErrorContainer = Color(0xFF5C1010),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA9A5FF),
    onPrimary = Color(0xFF1B1668),
    primaryContainer = Color(0xFF2E2A8A),
    onPrimaryContainer = Color(0xFFE4E2FF),
    secondary = Color(0xFF5FD8C5),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF0E4A41),
    onSecondaryContainer = Color(0xFFBFF3EA),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE7E9F1),
    surface = Color(0xFF171A22),
    onSurface = Color(0xFFE7E9F1),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA7ACBF),
    outline = Color(0xFF6D7287),
    outlineVariant = Color(0xFF2B3040),
    errorContainer = Color(0xFF5C1A17),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun VoiceCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content
    )
}

private val speakerPalette = listOf(
    Color(0xFF6366F1), Color(0xFF0EA5A4), Color(0xFFE0752D),
    Color(0xFFC2415C), Color(0xFF7C3AED), Color(0xFF3B82F6)
)

fun speakerColor(name: String): Color {
    val n = name.filter { it.isDigit() }.toIntOrNull() ?: (name.hashCode() and 0xFF)
    return speakerPalette[(n + speakerPalette.size - 1).mod(speakerPalette.size)]
}
