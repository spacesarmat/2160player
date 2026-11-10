package tv.p2160.core.engine

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTimestampWorkaroundTest {

    @Test
    fun realtekApi28CompressedPassthroughIgnoresTimestamp() {
        assertTrue(ignoreAudioTimestamp("Realtek", 28, C.ENCODING_AC3, tunneling = false, offload = false))
        assertTrue(ignoreAudioTimestamp("Realtek", 28, C.ENCODING_DTS, tunneling = false, offload = false))
    }

    @Test
    fun pcmKeepsDefaultClock() {
        assertFalse(ignoreAudioTimestamp("Realtek", 28, C.ENCODING_PCM_16BIT, tunneling = false, offload = false))
        assertFalse(ignoreAudioTimestamp("Realtek", 28, C.ENCODING_PCM_FLOAT, tunneling = false, offload = false))
    }

    @Test
    fun tunnelingAndOffloadKeepDefaultClock() {
        assertFalse(ignoreAudioTimestamp("Realtek", 28, C.ENCODING_AC3, tunneling = true, offload = false))
        assertFalse(ignoreAudioTimestamp("Realtek", 28, C.ENCODING_AC3, tunneling = false, offload = true))
    }

    @Test
    fun playbackHeadClockCounts32BitWrap() {
        assertEquals(100L, playbackHeadFrames(100, 50, 0))
        assertEquals(4_294_967_396L, playbackHeadFrames(100, 4_000_000_000L, 0))
        assertEquals(8_589_934_692L, playbackHeadFrames(100, 4_000_000_000L, 1))
    }

    @Test
    fun stopKeepsPlayingOutRemainingAudio() {
        val clock = PlaybackHeadClock()
        clock.stop(48_000, 48_000, 1_000_000_000L)
        assertEquals(1_500_000L, clock.positionUs(0, 48_000, 1_500_000_000L))
        assertEquals(2_000_000L, clock.positionUs(0, 48_000, 2_000_000_000L))
    }

    @Test
    fun stopIsIdempotent() {
        val clock = PlaybackHeadClock()
        clock.stop(48_000, 48_000, 1_000_000_000L)
        clock.stop(96_000, 48_000, 2_000_000_000L)
        assertEquals(3_000_000L, clock.positionUs(0, 48_000, 3_000_000_000L))
    }

    @Test
    fun flushResetsStopAndWrapState() {
        val clock = PlaybackHeadClock()
        clock.stop(4_000_000_000L, 48_000, 1_000_000_000L)
        clock.flush()
        assertEquals(100_000L, clock.positionUs(4_800, 48_000, 2_000_000_000L))
    }

    @Test
    fun otherDevicesAndApiLevelsKeepDefaultClock() {
        assertFalse(ignoreAudioTimestamp("Amlogic", 28, C.ENCODING_AC3, tunneling = false, offload = false))
        assertFalse(ignoreAudioTimestamp("Realtek", 29, C.ENCODING_AC3, tunneling = false, offload = false))
        assertFalse(ignoreAudioTimestamp("Realtek", 27, C.ENCODING_AC3, tunneling = false, offload = false))
    }
}
