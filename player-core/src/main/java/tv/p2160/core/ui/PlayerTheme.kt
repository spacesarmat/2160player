package tv.p2160.core.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Оформление плеера и приложения. Темы можно добавлять, расширяя [PlayerThemes.all]. */
@Immutable
data class PlayerTheme(
    val id: String,
    /** Ключ локализации названия темы. */
    val nameKey: String,
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val muted: Color,
    /** Полупрозрачная подложка под элементы управления поверх видео. */
    val scrim: Color,
    val isLight: Boolean = false,
) {
    val colorScheme: ColorScheme
        get() = if (isLight) {
            lightColorScheme(
                primary = accent, onPrimary = onAccent,
                background = background, surface = surface, onSurface = onSurface, onBackground = onSurface,
                surfaceVariant = surface.copy(alpha = 0.9f), onSurfaceVariant = muted,
                secondaryContainer = accent.copy(alpha = 0.18f), onSecondaryContainer = onSurface,
            )
        } else {
            darkColorScheme(
                primary = accent, onPrimary = onAccent,
                background = background, surface = surface, onSurface = onSurface, onBackground = onSurface,
                surfaceVariant = surface, onSurfaceVariant = muted,
                secondaryContainer = accent.copy(alpha = 0.22f), onSecondaryContainer = onSurface,
            )
        }
}

object PlayerThemes {
    val Cinema = PlayerTheme(
        id = "cinema", nameKey = "theme.cinema",
        accent = Color(0xFFFFB020), onAccent = Color(0xFF1A1200),
        background = Color(0xFF0E0E10), surface = Color(0xFF1A1A1E),
        onSurface = Color(0xFFF2F2F2), muted = Color(0xFF9A9AA2),
        scrim = Color(0xB3000000),
    )
    val Ocean = PlayerTheme(
        id = "ocean", nameKey = "theme.ocean",
        accent = Color(0xFF3FB6FF), onAccent = Color(0xFF00213A),
        background = Color(0xFF0A1220), surface = Color(0xFF14203A),
        onSurface = Color(0xFFE8F1FF), muted = Color(0xFF8FA3C0),
        scrim = Color(0xB3020814),
    )
    val Ruby = PlayerTheme(
        id = "ruby", nameKey = "theme.ruby",
        accent = Color(0xFFFF4D6A), onAccent = Color(0xFF2B0008),
        background = Color(0xFF120A0C), surface = Color(0xFF221418),
        onSurface = Color(0xFFFBECEF), muted = Color(0xFFB0959B),
        scrim = Color(0xB3100004),
    )
    val Mint = PlayerTheme(
        id = "mint", nameKey = "theme.mint",
        accent = Color(0xFF34D399), onAccent = Color(0xFF002417),
        background = Color(0xFF0B1210), surface = Color(0xFF15211D),
        onSurface = Color(0xFFE9F7F1), muted = Color(0xFF8CA89D),
        scrim = Color(0xB3000805),
    )
    val Amoled = PlayerTheme(
        id = "amoled", nameKey = "theme.amoled",
        accent = Color(0xFFFFFFFF), onAccent = Color(0xFF000000),
        background = Color(0xFF000000), surface = Color(0xFF101010),
        onSurface = Color(0xFFFFFFFF), muted = Color(0xFF8A8A8A),
        scrim = Color(0xCC000000),
    )
    val Light = PlayerTheme(
        id = "light", nameKey = "theme.light",
        accent = Color(0xFF2563EB), onAccent = Color(0xFFFFFFFF),
        background = Color(0xFFF6F7F9), surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF111318), muted = Color(0xFF5D6472),
        scrim = Color(0xB3000000), isLight = true,
    )

    val all = listOf(Cinema, Ocean, Ruby, Mint, Amoled, Light)

    fun byId(id: String): PlayerTheme = all.firstOrNull { it.id == id } ?: Cinema
}

@Composable
fun P2160Theme(theme: PlayerTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = theme.colorScheme, content = content)
}
