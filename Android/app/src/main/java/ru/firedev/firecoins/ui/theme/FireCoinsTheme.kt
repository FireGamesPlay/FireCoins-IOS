package ru.firedev.firecoins.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val FcBackground = Color(0xFF0C0E11)
val FcSurface = Color(0xFF15191E)
val FcSurfaceElevated = Color(0xFF1C2229)
val FcBorder = Color(0xFF292F36)
val FcAccent = Color(0xFFD5A16D)
val FcAccentLight = Color(0xFFE8BE91)
val FcText = Color(0xFFF3F0EB)
val FcMuted = Color(0xFF9299A2)
val FcPositive = Color(0xFF8FBE9A)
val FcNegative = Color(0xFFD97D72)
val FcBlue = Color(0xFF8CA8C8)

private val FireCoinsColors = darkColorScheme(
    primary = FcAccent,
    onPrimary = Color(0xFF211A14),
    secondary = FcAccentLight,
    background = FcBackground,
    onBackground = FcText,
    surface = FcSurface,
    onSurface = FcText,
    surfaceVariant = FcSurfaceElevated,
    onSurfaceVariant = FcMuted,
    outline = FcBorder,
    error = FcNegative
)

@Composable
fun FireCoinsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FireCoinsColors,
        content = content
    )
}
