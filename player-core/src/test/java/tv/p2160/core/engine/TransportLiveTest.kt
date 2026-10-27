package tv.p2160.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.ui.UNKNOWN_TIME
import tv.p2160.core.ui.transportElapsedText

class TransportLiveTest {
    @Test
    fun liveBadgeOnlyForALiveItem() {
        assertFalse(transportIsLive(isCurrentMediaItemLive = false, isCurrentMediaItemDynamic = false, hasLiveConfiguration = false))
        assertFalse(transportIsLive(isCurrentMediaItemLive = false, isCurrentMediaItemDynamic = true, hasLiveConfiguration = false))
        assertTrue(transportIsLive(isCurrentMediaItemLive = true, isCurrentMediaItemDynamic = false, hasLiveConfiguration = false))
        assertTrue(transportIsLive(isCurrentMediaItemLive = false, isCurrentMediaItemDynamic = true, hasLiveConfiguration = true))
    }

    @Test
    fun unknownDurationHidesRemainingAndDoesNotLookLikeLive() {
        assertFalse(transportShowsRemaining(isLive = false, durationMs = 0))
        assertFalse(transportShowsRemaining(isLive = true, durationMs = 0))
        assertFalse(transportShowsRemaining(isLive = true, durationMs = 60_000))
        assertTrue(transportShowsRemaining(isLive = false, durationMs = 60_000))
        assertEquals(UNKNOWN_TIME, transportElapsedText(durationKnown = false, positionMs = 0))
        assertEquals("0:05", transportElapsedText(durationKnown = false, positionMs = 5_000))
        assertEquals("0:00", transportElapsedText(durationKnown = true, positionMs = 0))
        assertFalse(UNKNOWN_TIME.contains('\u2014'))
    }
}
