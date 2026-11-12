package tv.p2160.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Тема «Кино» из player-core: тёмный фон и янтарный акцент. */
object P2160Colors {
    val accent = Color(0xFFFFB020)
    val onAccent = Color(0xFF1A1200)
    val background = Color(0xFF0E0E10)
    val surface = Color(0xFF1A1A1E)
    val onSurface = Color(0xFFF2F2F2)
    val muted = Color(0xFF9A9AA2)
    val scrim = Color(0xB3000000)
}

private val scheme = darkColorScheme(
    primary = P2160Colors.accent,
    onPrimary = P2160Colors.onAccent,
    background = P2160Colors.background,
    onBackground = P2160Colors.onSurface,
    surface = P2160Colors.surface,
    onSurface = P2160Colors.onSurface,
    onSurfaceVariant = P2160Colors.muted,
    surfaceVariant = P2160Colors.surface,
)

@Composable
fun P2160Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
