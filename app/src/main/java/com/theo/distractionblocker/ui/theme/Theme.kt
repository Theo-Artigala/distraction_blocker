package com.theo.distractionblocker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme sombre unique. Pas de theme clair ni de couleurs dynamiques : c'est un
 * ecran de reglages qu'on ouvre trois fois par mois, l'important est qu'il soit
 * lisible.
 */
private val colors = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF47464F),
    onSurfaceVariant = Color(0xFFC9C5D0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun DistractionBlockerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
