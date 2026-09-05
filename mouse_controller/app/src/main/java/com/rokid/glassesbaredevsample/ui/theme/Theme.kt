package com.rokid.glassesbaredevsample.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val BareColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = PitchBlack,
    background = PitchBlack,
    onBackground = NeonGreen,
    surface = PitchBlack,
    onSurface = NeonGreen,
    outline = NeonGreen,
)

@Composable
fun MouseControlGlassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BareColorScheme,
        content = content,
    )
}

/** @deprecated Use [MouseControlGlassesTheme]. */
@Composable
fun GlassesBareDevSampleTheme(content: @Composable () -> Unit) = MouseControlGlassesTheme(content)
