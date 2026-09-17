package com.scharwenka.xperiamix.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Theme sombre par defaut : un plan de travail audio se lit mieux sur fond
 * sombre, et la dalle OLED du Xperia 1 V y consomme nettement moins.
 *
 * Les deux pistes sont distinguees par `primary` (A) et `tertiary` (B) : deux
 * teintes eloignees en chrominance *et* en luminance, pour rester lisibles en
 * superposition et pour les daltonismes rouge-vert.
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6ED3FF),        // cyan : piste A
    tertiary = Color(0xFFFFB86C),       // ambre : piste B
    error = Color(0xFFFF5C7A),          // curseur de lecture et alertes
    background = Color(0xFF0B0E12),
    surface = Color(0xFF12161C),
    surfaceVariant = Color(0xFF1B212A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00658F),
    tertiary = Color(0xFF8A5100),
    error = Color(0xFFB3261E),
)

@Composable
fun XperiaMixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
