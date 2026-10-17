package io.github.willianchavez.winali

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

@Composable
fun FamilyTheme(context: Context, content: @Composable () -> Unit) {
    val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    val mode = prefs.getString("theme", "system")
    val dark = mode == "dark" || (mode == "system" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme =
            if (dark)
                darkColorScheme(
                    primary = Color(0xFF74AAFF),
                    primaryContainer = Color(0xFF163D76),
                    onPrimaryContainer = Color(0xFFDCEBFF),
                    onSecondaryContainer = Color(0xFFDCEBFF),
                    secondaryContainer = Color(0xFF213650),
                    secondary = Color(0xFF90BCFF),
                    tertiary = Color(0xFF8EBFFF),
                    surfaceContainer = Color(0xFF171C25),
                    background = Color(0xFF101114),
                    surface = Color(0xFF1B1C21),
                )
            else
                lightColorScheme(
                    primary = Color(0xFF1677FF),
                    primaryContainer = Color(0xFFDCEBFF),
                    onPrimaryContainer = Color(0xFF113C77),
                    onSecondaryContainer = Color(0xFF173B68),
                    secondaryContainer = Color(0xFFE0EDFF),
                    secondary = Color(0xFF386398),
                    tertiary = Color(0xFF286DD8),
                    surfaceContainer = Color(0xFFEDF3FC),
                    background = Color(0xFFF6F7FB),
                    surface = Color.White,
                ),
        content = content,
    )
}

fun Context.es() = getSharedPreferences("appearance", 0).getString("language", "es") != "en"

fun Context.words(es: String, en: String) = if (es()) es else en
