package tv.p2160.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.ui.UNKNOWN_TIME
import tv.p2160.core.ui.transportElapsedText
import tv.p2160.core.ui.transportRemainingText

class TransportLiveTest {
    @Test
    fun liveBadgeOnlyForALiveItem() {
        assertFalse(transportIsLive(isCurrentMediaItemLive = false, isCurrentMediaItemDynamic = false, hasLiveConfiguration = false))
        assertFalse(transportIsLive(isCurrentMediaItemLive = false, isCurrentMediaItemDynamic = true, hasLiveConfiguration = false))
        assertTrue(transportIsLive(isCurrentMediaItemLive = true, isCurrentMediaItemDynamic = false, hasLiveConfiguration = false))
        assertTrue(transportIsLive(isCurrentMediaItemLive = false, isCurrentMediaItemDynamic = true, hasLiveConfiguration = true))
        assertFalse(transportShowsLiveBadge(requestedLive = false, playerSaysLive = true, durationMs = 0, playbackReady = false))
        assertTrue(transportShowsLiveBadge(requestedLive = false, playerSaysLive = true, durationMs = 0, playbackReady = true))
        assertTrue(transportShowsLiveBadge(requestedLive = true, playerSaysLive = false, durationMs = 0, playbackReady = false))
        assertFalse(transportShowsLiveBadge(requestedLive = false, playerSaysLive = false, durationMs = 90_000, playbackReady = true))
    }

    @Test
    fun unknownDurationKeepsARemainingPlaceholderAndDoesNotLookLikeLive() {
        assertTrue(transportShowsRemaining(isLive = false, durationMs = 0))
        assertTrue(transportShowsRemaining(isLive = false, durationMs = -1))
        assertFalse(transportShowsRemaining(isLive = true, durationMs = 0))
        assertFalse(transportShowsRemaining(isLive = true, durationMs = 60_000))
        assertTrue(transportShowsRemaining(isLive = false, durationMs = 60_000))
        assertEquals(UNKNOWN_TIME, transportElapsedText(durationKnown = false, positionMs = 0))
        assertEquals("0:05", transportElapsedText(durationKnown = false, positionMs = 5_000))
        assertEquals("0:00", transportElapsedText(durationKnown = true, positionMs = 0))
        assertEquals("--:--", transportRemainingText(durationKnown = false, positionMs = 0, durationMs = 0))
        assertEquals("--:--", transportRemainingText(durationKnown = false, positionMs = 209_000, durationMs = -1))
        assertEquals("\u22120:30 / 1:00", transportRemainingText(durationKnown = true, positionMs = 30_000, durationMs = 60_000))
        assertEquals("\u22122:32:35 / 2:36:31", transportRemainingText(durationKnown = true, positionMs = 236_000, durationMs = 9_391_000))
        assertFalse(UNKNOWN_TIME.contains('\u2014'))
        assertFalse(transportRemainingText(durationKnown = false, positionMs = 0, durationMs = 0).contains('\u2013'))
    }
}
