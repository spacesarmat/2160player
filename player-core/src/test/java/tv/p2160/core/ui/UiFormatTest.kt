package tv.p2160.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UiFormatTest {
    @Test
    fun speedUsesLocaleSeparatorAndTrimsZeros() {
        assertEquals("1", UiFormat.speed(1f, Locale.US))
        assertEquals("1.5", UiFormat.speed(1.5f, Locale.US))
        assertEquals("0.25", UiFormat.speed(0.25f, Locale.US))
        assertEquals("1,5", UiFormat.speed(1.5f, Locale("ru", "RU")))
        assertEquals("0,25", UiFormat.speed(0.25f, Locale("ru", "RU")))
    }

    @Test
    fun fineSpeedAndZoomKeepFixedFractionDigits() {
        assertEquals("1.00", UiFormat.speedFine(1f, Locale.US))
        assertEquals("1,00", UiFormat.speedFine(1f, Locale("ru", "RU")))
        assertEquals("1.0", UiFormat.zoom(1f, Locale.US))
        assertEquals("1,0", UiFormat.zoom(1f, Locale("ru", "RU")))
    }

    @Test
    fun formatSpeedFollowsDefaultLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale("ru", "RU"))
        try {
            assertEquals("1,25", formatSpeed(1.25f))
            assertEquals("2", formatSpeed(2f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun sidePanelIsFractionClampedToRange() {
        assertEquals(358.26f, sidePanelWidthDp(853f), 0.01f)
        assertEquals(420f, sidePanelWidthDp(1200f), 0.01f)
        assertEquals(300f, sidePanelWidthDp(700f), 0.01f)
        assertEquals(280f, sidePanelWidthDp(280f), 0.01f)
        assertEquals(0f, sidePanelWidthDp(0f), 0.01f)
    }
}
