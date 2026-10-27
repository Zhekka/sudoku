package com.sudoku.recognizer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF15151A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDF1),
    onPrimaryContainer = Color(0xFF15151A),
    secondary = Color(0xFF2F6BE6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3ECFE),
    onSecondaryContainer = Color(0xFF123A85),
    background = Color(0xFFFCFCFD),
    onBackground = Color(0xFF15151A),
    surface = Color(0xFFFCFCFD),
    onSurface = Color(0xFF15151A),
    surfaceVariant = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF5C5C66),
    outline = Color(0xFFC6C6CE),
    outlineVariant = Color(0xFFDFDFE5),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F6),
    onPrimary = Color(0xFF15151A),
    primaryContainer = Color(0xFF26262D),
    onPrimaryContainer = Color(0xFFF2F2F6),
    secondary = Color(0xFF86A9F5),
    onSecondary = Color(0xFF0B1B3B),
    secondaryContainer = Color(0xFF1D2F55),
    onSecondaryContainer = Color(0xFFDCE7FF),
    background = Color(0xFF121215),
    onBackground = Color(0xFFECECF0),
    surface = Color(0xFF121215),
    onSurface = Color(0xFFECECF0),
    surfaceVariant = Color(0xFF26262D),
    onSurfaceVariant = Color(0xFFA9A9B4),
    outline = Color(0xFF46464F),
    outlineVariant = Color(0xFF32323A),
    error = Color(0xFFFF8A80),
)

@Composable
fun SudokuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
