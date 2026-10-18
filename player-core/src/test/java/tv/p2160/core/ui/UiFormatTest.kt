package tv.p2160.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun sidePanelStartsBelowTheTopBar() {
        assertEquals(64f, sidePanelTopClearanceDp(narrow = false), 0.01f)
        assertEquals(112f, sidePanelTopClearanceDp(narrow = true), 0.01f)
    }

    @Test
    fun timeSampleFollowsTheDurationFormat() {
        assertEquals("8:88", elapsedTimeSample(5 * 60_000L))
        assertEquals("8:88", elapsedTimeSample(9 * 60_000L + 59_000L))
        assertEquals("88:88", elapsedTimeSample(10 * 60_000L))
        assertEquals("88:88", elapsedTimeSample(59 * 60_000L))
        assertEquals("8:88:88", elapsedTimeSample(60 * 60_000L))
        assertEquals("88:88:88", elapsedTimeSample(10 * 60 * 60_000L))
        assertEquals("−8:88 / 8:88", remainingTimeSample(5 * 60_000L))
        assertEquals("−8:88:88 / 8:88:88", remainingTimeSample(60 * 60_000L))
        assertTrue(remainingTimeSample(60 * 60_000L).length < REMAINING_SAMPLE.length)
    }

    @Test
    fun tickTextSitsAtTheStartOrTheEndOfItsBox() {
        assertEquals(0f, tickTextX(200f, 80f, alignEnd = false), 0.01f)
        assertEquals(120f, tickTextX(200f, 80f, alignEnd = true), 0.01f)
        assertEquals(0f, tickTextX(40f, 80f, alignEnd = true), 0.01f)
    }

    @Test
    fun openPanelLeavesAGapBeforeTheTimeLabel() {
        assertEquals(12f, sidePanelTransportGapDp, 0.01f)
    }
}
