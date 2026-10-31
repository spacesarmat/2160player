package tv.p2160.core.engine

import androidx.media3.common.Player
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
    fun resumeToastDoesNotCoverTheUpNextCard() {
        assertTrue(upNextCardReservesSide(872))
        assertFalse(upNextCardReservesSide(392))
        assertFalse(deferUpNextWhileResumeToast(true, 872, 392, true))
        assertFalse(deferUpNextWhileResumeToast(true, 392, 872, true))
        assertFalse(deferUpNextWhileResumeToast(true, 960, 540, true))
        assertTrue(deferUpNextWhileResumeToast(true, 360, 400, true))
        assertFalse(deferUpNextWhileResumeToast(false, 360, 400, true))
    }

    @Test
    fun stayOnFrameDimsOnlyAfterTheTimeout() {
        assertFalse(endShouldDim(holdingEnd = true, live = false, heldForMs = 60_000))
        assertTrue(endShouldDim(holdingEnd = true, live = false, heldForMs = END_DIM_MS))
        assertFalse(endShouldDim(holdingEnd = true, live = true, heldForMs = END_DIM_MS))
        assertFalse(endShouldDim(holdingEnd = false, live = false, heldForMs = END_DIM_MS))
    }

    @Test
    fun naturalEndNeedsAKnownDurationInsideTheFinishTolerance() {
        val episode = 24 * 60_000L + 1_000L
        assertFalse(verifiedNaturalEnd(80_000L, episode))
        assertFalse(verifiedNaturalEnd(80_000L, 0L))
        assertFalse(verifiedNaturalEnd(80_000L, -1L))
        assertFalse(verifiedNaturalEnd(0L, episode))
        assertTrue(verifiedNaturalEnd(episode, episode))
        assertTrue(verifiedNaturalEnd(episode - 20_000L, episode))
        assertFalse(verifiedNaturalEnd(episode / 2, episode))
        // Короткое видео и более длинный внешний звук: конец - это длительность видео.
        assertTrue(verifiedNaturalEnd(5_000L, 5_000L))
        assertTrue(verifiedNaturalEnd(20_000L, 20_000L))
    }

    @Test
    fun earlyStreamEndDoesNotPromoteTheResultPosition() {
        val episode = 24 * 60_000L + 1_000L
        val early = playbackReport(80_000L, episode, verifiedEnd = false)
        assertEquals(80_000L, early.positionMs)
        assertEquals(episode, early.durationMs)
        assertFalse(early.completed)
        val done = playbackReport(episode - 1_000L, episode, verifiedEnd = true)
        assertEquals(episode, done.positionMs)
        assertTrue(done.completed)
        val near = playbackReport(episode - 10_000L, episode, verifiedEnd = false)
        assertEquals(episode, near.positionMs)
        assertTrue(near.completed)
        assertFalse(prematureEndExhausted(0))
        assertFalse(prematureEndExhausted(PREMATURE_END_RETRIES - 1))
        assertTrue(prematureEndExhausted(PREMATURE_END_RETRIES))
    }

    @Test
    fun pauseSaverCountsARealPauseAndNotBuffering() {
        assertFalse(countsAsUserPause(playWhenReady = true, playbackState = Player.STATE_BUFFERING))
        assertFalse(countsAsUserPause(playWhenReady = true, playbackState = Player.STATE_READY))
        assertFalse(countsAsUserPause(playWhenReady = false, playbackState = Player.STATE_BUFFERING))
        assertFalse(countsAsUserPause(playWhenReady = false, playbackState = Player.STATE_ENDED))
        assertFalse(countsAsUserPause(playWhenReady = false, playbackState = Player.STATE_IDLE))
        assertTrue(countsAsUserPause(playWhenReady = false, playbackState = Player.STATE_READY))
        assertFalse(
            pauseSaverShouldDim(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
                oledSaver = true,
                heldForMs = END_DIM_MS,
                blocked = false,
            ),
        )
        assertFalse(
            pauseSaverShouldDim(
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                oledSaver = true,
                heldForMs = END_DIM_MS - 1,
                blocked = false,
            ),
        )
        assertFalse(
            pauseSaverShouldDim(
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                oledSaver = false,
                heldForMs = END_DIM_MS,
                blocked = false,
            ),
        )
        assertTrue(
            pauseSaverShouldDim(
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                oledSaver = true,
                heldForMs = END_DIM_MS,
                blocked = false,
            ),
        )
        assertTrue(playPauseWhenUnfocused(controlsVisible = true, rootFocused = false, windowHasFocus = false))
        assertTrue(playPauseWhenUnfocused(controlsVisible = true, rootFocused = true, windowHasFocus = true))
        assertFalse(playPauseWhenUnfocused(controlsVisible = true, rootFocused = false, windowHasFocus = true))
        assertFalse(playPauseWhenUnfocused(controlsVisible = false, rootFocused = false, windowHasFocus = false))
    }
}
