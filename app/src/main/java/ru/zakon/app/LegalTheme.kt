package ru.zakon.app

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal val LegalNavy = Color(0xFF17283F)
internal val LegalNavyLight = Color(0xFF243B58)
internal val LegalGold = Color(0xFFC49A52)
internal val LegalPaper = Color(0xFFF5F3EE)
internal val LegalSurface = Color(0xFFFFFEFC)
internal val LegalLine = Color(0xFFE5E1D8)
internal val LegalMuted = Color(0xFF6D7784)
internal val LegalColors = lightColorScheme(
    primary = LegalNavy, secondary = LegalGold, background = LegalPaper,
    surface = LegalSurface, onPrimary = Color.White, onSecondary = LegalNavy,
    onBackground = LegalNavy, onSurface = LegalNavy, outline = LegalLine,
    onSurfaceVariant = LegalMuted
)
