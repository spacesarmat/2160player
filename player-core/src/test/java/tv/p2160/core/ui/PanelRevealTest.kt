package tv.p2160.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.settings.ResizeMode

class PanelRevealTest {
    @Test
    fun normalSpeedIsBelowTheFineStepper() {
        // 0 — «Точно», дальше пресеты: 0,25 0,5 0,75 1 …
        assertEquals(4, speedRevealIndex(1f))
        assertEquals(1, speedRevealIndex(0.25f))
        assertNull(speedRevealIndex(1.05f))
    }

    @Test
    fun resizeFollowsTheScaleHeading() {
        assertEquals(1, resizeRevealIndex(ResizeMode.FIT))
        assertEquals(4, resizeRevealIndex(ResizeMode.ZOOM))
    }

    @Test
    fun audioTrackComesAfterDelayHintAndOptionalSchedule() {
        assertEquals(4, audioTrackRevealIndex(nightAuto = false, trackCount = 2, selectedTrack = 0))
        assertEquals(7, audioTrackRevealIndex(nightAuto = true, trackCount = 2, selectedTrack = 1))
        assertNull(audioTrackRevealIndex(nightAuto = false, trackCount = 0, selectedTrack = -1))
    }

    @Test
    fun disabledSubtitlesStayOnTheOffRow() {
        assertEquals(0, subtitleRevealIndex(disabled = true, primaryCount = 3, selectedInPrimary = 1))
        assertEquals(2, subtitleRevealIndex(disabled = false, primaryCount = 3, selectedInPrimary = 1))
    }

    @Test
    fun visibleRowIsNotScrolled() {
        assertEquals(0, revealDelta(offset = 40, size = 80, viewportStart = 0, viewportEnd = 400))
    }

    @Test
    fun clippedRowMovesOnlyByTheOverflow() {
        assertEquals(30, revealDelta(offset = 350, size = 80, viewportStart = 0, viewportEnd = 400))
        assertEquals(-20, revealDelta(offset = -20, size = 80, viewportStart = 0, viewportEnd = 400))
    }

    @Test
    fun shortPanelIsAColumnAndALongOneStaysLazy() {
        assertFalse(panelUsesLazyList(12))
        assertFalse(panelUsesLazyList(PANEL_EAGER_ROW_LIMIT))
        assertTrue(panelUsesLazyList(PANEL_EAGER_ROW_LIMIT + 1))
    }

    @Test
    fun columnRevealMatchesTheLazyOverflow() {
        assertEquals(0f, panelBringIntoViewDistance(40f, 80f, 400f), 0.01f)
        assertEquals(30f, panelBringIntoViewDistance(350f, 80f, 400f), 0.01f)
        assertEquals(-20f, panelBringIntoViewDistance(-20f, 80f, 400f), 0.01f)
        assertEquals(0f, panelBringIntoViewDistance(-10f, 500f, 400f), 0.01f)
    }

    @Test
    fun offscreenRowIsPulledBackToTheBottomEdge() {
        // После scrollToItem строка у верхнего края окна 400 px высотой. Отмотка 320 px сажает её вниз.
        assertEquals(320, revealPullBack(offset = 0, size = 80, viewportStart = 0, viewportEnd = 400))
        assertEquals(0, revealPullBack(offset = 0, size = 500, viewportStart = 0, viewportEnd = 400))
    }
}
