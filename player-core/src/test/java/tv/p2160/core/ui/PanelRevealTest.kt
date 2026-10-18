package tv.p2160.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
