package tv.p2160.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.settings.EndAction

class EndPlaybackTest {
    @Test
    fun nextCountsDownThenShowsTheEndScreenOnTheLastItem() {
        assertTrue(endPlan(EndAction.NEXT, hasNext = true, live = false).countdown)
        assertTrue(endPlan(EndAction.NEXT, hasNext = false, live = false).endScreen)
        assertFalse(endPlan(EndAction.NEXT, hasNext = true, live = false).endScreen)
    }

    @Test
    fun liveStaysPutForEveryAction() {
        EndAction.entries.forEach { action ->
            val plan = endPlan(action, hasNext = true, live = true)
            assertTrue(plan.stay)
            assertFalse(plan.countdown)
            assertFalse(plan.endScreen)
            assertFalse(plan.closeNow)
            assertFalse(plan.repeat)
        }
    }

    @Test
    fun closeRepeatAndStay() {
        assertTrue(endPlan(EndAction.CLOSE, hasNext = true, live = false).closeNow)
        assertTrue(endPlan(EndAction.REPEAT, hasNext = true, live = false).repeat)
        assertTrue(endPlan(EndAction.STAY, hasNext = true, live = false).stay)
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
