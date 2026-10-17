package tv.p2160.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.ui.shouldKeepScreenOn

class ScreenWakeTest {
    private fun wake(
        enabled: Boolean = true,
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        playWhenReady: Boolean = false,
        ended: Boolean = false,
        hasError: Boolean = false,
        hasVideo: Boolean = true,
        isLive: Boolean = false,
    ) = shouldKeepScreenOn(enabled, isPlaying, isBuffering, playWhenReady, ended, hasError, hasVideo, isLive)

    @Test
    fun holdsOnlyWhileVideoOrLiveIsRunning() {
        assertTrue(wake(isPlaying = true))
        assertTrue(wake(isBuffering = true, playWhenReady = true))
        assertTrue(wake(isPlaying = true, hasVideo = false, isLive = true))
        assertFalse(wake(enabled = false, isPlaying = true))
        assertFalse(wake(isPlaying = false, isBuffering = false))
        assertFalse(wake(isBuffering = true, playWhenReady = false))
        assertFalse(wake(isPlaying = true, ended = true))
        assertFalse(wake(isPlaying = true, hasError = true))
        assertFalse(wake(isPlaying = true, hasVideo = false, isLive = false))
    }
}
