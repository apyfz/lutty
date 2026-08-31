package com.apyfz.lutty.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.apyfz.lutty.R

/**
 * Deliberately monochrome.
 *
 * This is a colour grading tool, so any hue in the interface competes with the image and biases
 * judgement. Everything is neutral grey; only errors carry colour, because they must not be
 * missed. Dynamic colour is off for the same reason — the palette must not follow the wallpaper,
 * and the app is dark always, so the surround never shifts how the image reads.
 */

private val Dark = darkColorScheme(
    primary = Color(0xFFE8E8E9),
    onPrimary = Color(0xFF1A1A1C),
    primaryContainer = Color(0xFF303033),
    onPrimaryContainer = Color(0xFFF2F2F3),
    secondary = Color(0xFFBFBFC2),
    onSecondary = Color(0xFF1A1A1C),
    secondaryContainer = Color(0xFF2A2A2D),
    onSecondaryContainer = Color(0xFFE8E8E9),
    tertiary = Color(0xFFBFBFC2),
    onTertiary = Color(0xFF1A1A1C),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE8E8E9),
    surface = Color(0xFF0D0D0E),
    onSurface = Color(0xFFE8E8E9),
    surfaceVariant = Color(0xFF2A2A2D),
    onSurfaceVariant = Color(0xFFA8A8AC),
    surfaceContainerLowest = Color(0xFF080809),
    surfaceContainerLow = Color(0xFF121213),
    surfaceContainer = Color(0xFF161617),
    surfaceContainerHigh = Color(0xFF1F1F21),
    surfaceContainerHighest = Color(0xFF29292B),
    outline = Color(0xFF56565A),
    outlineVariant = Color(0xFF353538),
    inverseSurface = Color(0xFFE8E8E9),
    inverseOnSurface = Color(0xFF1A1A1C),
    error = Color(0xFFE79A94),
    onError = Color(0xFF40100C),
)

// Geist (Vercel), a variable font — one file, weights pulled from the wght axis.
private fun geist(weight: Int) = Font(
    R.font.geist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))

/** Material's default type scale, re-pointed at Geist for every style. */
private val GeistTypography: Typography = Typography().run {
    fun androidx.compose.ui.text.TextStyle.g() = copy(fontFamily = Geist)
    copy(
        displayLarge = displayLarge.g(), displayMedium = displayMedium.g(), displaySmall = displaySmall.g(),
        headlineLarge = headlineLarge.g(), headlineMedium = headlineMedium.g(), headlineSmall = headlineSmall.g(),
        titleLarge = titleLarge.g(), titleMedium = titleMedium.g(), titleSmall = titleSmall.g(),
        bodyLarge = bodyLarge.g(), bodyMedium = bodyMedium.g(), bodySmall = bodySmall.g(),
        labelLarge = labelLarge.g(), labelMedium = labelMedium.g(), labelSmall = labelSmall.g(),
    )
}

@Composable
fun LutBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Dark,
        typography = GeistTypography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
