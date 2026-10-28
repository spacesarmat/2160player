package tv.p2160.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.settings.EndAction

class EndPlaybackTest {
    @Test
    fun nextCountsDownThenEndsThePlaylistOrTheSingleFile() {
        assertTrue(endPlan(EndAction.NEXT, hasNext = true, playlistSize = 3, live = false).countdown)
        assertTrue(endPlan(EndAction.NEXT, hasNext = false, playlistSize = 3, live = false).playlistDone)
        assertTrue(endPlan(EndAction.NEXT, hasNext = false, playlistSize = 1, live = false).endScreen)
        assertFalse(endPlan(EndAction.NEXT, hasNext = true, playlistSize = 3, live = false).endScreen)
    }

    @Test
    fun liveStaysPutForEveryAction() {
        EndAction.entries.forEach { action ->
            val plan = endPlan(action, hasNext = true, playlistSize = 2, live = true)
            assertTrue(plan.stay)
            assertFalse(plan.countdown)
            assertFalse(plan.endScreen)
            assertFalse(plan.playlistDone)
            assertFalse(plan.closeNow)
            assertFalse(plan.repeat)
        }
    }

    @Test
    fun closeRepeatAndStay() {
        assertTrue(endPlan(EndAction.CLOSE, hasNext = true, playlistSize = 2, live = false).closeNow)
        assertTrue(endPlan(EndAction.REPEAT, hasNext = true, playlistSize = 2, live = false).repeat)
        assertTrue(endPlan(EndAction.STAY, hasNext = true, playlistSize = 2, live = false).stay)
    }

    @Test
    fun upNextAppearsBeforeTheEndAndOnCredits() {
        assertTrue(showUpNext(EndAction.NEXT, true, false, false, 90_000, 100_000, false, null))
        assertTrue(showUpNext(EndAction.NEXT, true, false, false, 85_000, 100_000, false, null))
        assertFalse(showUpNext(EndAction.NEXT, true, false, false, 84_000, 100_000, false, null))
        assertTrue(showUpNext(EndAction.NEXT, true, false, false, 10_000, 100_000, true, "Титры"))
        assertFalse(showUpNext(EndAction.NEXT, true, true, false, 90_000, 100_000, false, null))
        assertFalse(showUpNext(EndAction.STAY, true, false, false, 90_000, 100_000, false, null))
        assertTrue(looksLikeCredits("End credits"))
        assertFalse(looksLikeCredits("Chapter 2"))
    }

    @Test
    fun resumeOfferKeepsExplicitStartAndFinishedFiles() {
        assertEquals(23_000L, resumeOfferMs(true, null, 23_000L, false, false))
        assertNull(resumeOfferMs(false, null, 23_000L, false, false))
        assertNull(resumeOfferMs(true, 0L, 23_000L, false, false))
        assertNull(resumeOfferMs(true, null, 23_000L, true, false))
        assertNull(resumeOfferMs(true, null, 23_000L, false, true))
        assertNull(resumeOfferMs(true, null, 4_000L, false, false))
    }

    @Test
    fun legacyAutoNextMapsOntoTheNewSetting() {
        assertEquals(EndAction.NEXT, endActionFromLegacy(autoPlayNext = true))
        assertEquals(EndAction.STAY, endActionFromLegacy(autoPlayNext = false))
    }

    @Test
    fun stayOnFrameDimsOnlyAfterTheTimeout() {
        assertFalse(endShouldDim(holdingEnd = true, live = false, heldForMs = 60_000))
        assertTrue(endShouldDim(holdingEnd = true, live = false, heldForMs = END_DIM_MS))
        assertFalse(endShouldDim(holdingEnd = true, live = true, heldForMs = END_DIM_MS))
        assertFalse(endShouldDim(holdingEnd = false, live = false, heldForMs = END_DIM_MS))
    }
}
