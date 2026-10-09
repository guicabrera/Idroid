package com.idroid.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * Idroid's own palette — indigo to violet, the same gradient as the app
 * icon (res/drawable/ic_launcher_background.xml) and the Mac app's icon —
 * instead of Material You's wallpaper colors, so the app looks the same on
 * every phone and matches the Mac side.
 */
object IdroidColors {
    val Indigo = Color(0xFF4F46E5)
    val Violet = Color(0xFF8B5CF6)
    val Online = Color(0xFF22C55E)

    /** The icon's gradient, for hero surfaces. */
    val BrandGradient = Brush.linearGradient(listOf(Indigo, Violet))
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0D9488),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF2E1065),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF151823),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF151823),
    surfaceVariant = Color(0xFFE6E8F0),
    onSurfaceVariant = Color(0xFF4B5163),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F2F8),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE2E4EE),
    outline = Color(0xFF767B8C),
    outlineVariant = Color(0xFFD3D6E0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF5B21B6),
    onTertiaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE6E8F0),
    surface = Color(0xFF0F1117),
    onSurface = Color(0xFFE6E8F0),
    surfaceVariant = Color(0xFF2A2E3A),
    onSurfaceVariant = Color(0xFFC3C7D3),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF151821),
    surfaceContainer = Color(0xFF191C26),
    surfaceContainerHigh = Color(0xFF1D2130),
    surfaceContainerHighest = Color(0xFF272B38),
    outline = Color(0xFF8C91A1),
    outlineVariant = Color(0xFF353A48),
)

private val IdroidTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun IdroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = IdroidTypography,
        content = content,
    )
}
