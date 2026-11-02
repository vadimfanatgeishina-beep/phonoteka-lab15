package com.phonoteka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEEEBFC),
    onPrimaryContainer = Color(0xFF282631),
    secondary = Color(0xFF5C4FAE),
    onSecondary = Color.White,
    background = Color(0xFFF5F4F1),
    onBackground = Color(0xFF282631),
    surface = Color.White,
    onSurface = Color(0xFF282631),
    error = Color(0xFFC0392B),
    onError = Color.White,
    surfaceVariant = Color(0xFFE5E2E9),
    onSurfaceVariant = Color(0xFF736F7F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB5A0FF),
    onPrimary = Color(0xFF1A0E4A),
    primaryContainer = Color(0xFF3A2A6E),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Color(0xFF9F8FE5),
    onSecondary = Color(0xFF1A0E4A),
    background = Color(0xFF1A181E),
    onBackground = Color(0xFFE5E2E9),
    surface = Color(0xFF252329),
    onSurface = Color(0xFFE5E2E9),
    error = Color(0xFFFF6B5B),
    onError = Color(0xFF1A0E0D),
    surfaceVariant = Color(0xFF3D3A43),
    onSurfaceVariant = Color(0xFFB5B0BF)
)

private val PhonotekaTypography = Typography().copy(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
)

private val PhonotekaShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

/** Тема Фонотеки: следует за системной светлой/тёмной темой, переключателя в приложении нет. */
@Composable
fun PhonotekaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PhonotekaTypography,
        shapes = PhonotekaShapes,
        content = content
    )
}
