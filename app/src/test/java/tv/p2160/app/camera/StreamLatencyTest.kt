package tv.p2160.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLatencyTest {
    @Test
    fun defaultsStayLongerThanTheLowLatencyProfile() {
        assertEquals(120, StreamLatency.sendQueue(false))
        assertEquals(30, StreamLatency.sendQueue(true))
        assertEquals(120, StreamLatency.srtLatencyMs(false))
        assertEquals(80, StreamLatency.srtLatencyMs(true))
        assertEquals(3, StreamLatency.ndiImages(false))
        assertEquals(2, StreamLatency.ndiImages(true))
        assertNull(StreamLatency.rtspSendBuffer(false))
        assertEquals(64 * 1024, StreamLatency.rtspSendBuffer(true))
        assertFalse(StreamLatency.shouldDropStale(false, 100, 120))
        assertFalse(StreamLatency.shouldDropStale(true, 10, 30))
        assertTrue(StreamLatency.shouldDropStale(true, 15, 30))
    }

    @Test
    fun videoTimecodeIs100nsAndMissingStaysSynthesized() {
        assertEquals(10_000_000L, StreamLatency.videoTimecode100ns(1_000_000_000L))
        assertEquals(StreamLatency.SYNTHESIZE, StreamLatency.videoTimecode100ns(0))
        assertEquals(StreamLatency.SYNTHESIZE, StreamLatency.videoTimecode100ns(-1))
    }

    @Test
    fun audioBeforeTheFirstFrameLinesUpWithThatFrame() {
        val clock = NdiClock()
        // 4410 стерео-сэмплов = 0,1 с при 44,1 кГц, ещё без кадра.
        assertEquals(StreamLatency.SYNTHESIZE, clock.onAudio(17_640, 44_100, 2))
        assertEquals(10_000_000L, clock.onVideo(1_000_000_000L))
        // Следующий такой же кусок начинается на времени первого кадра, а не убегает вперёд.
        assertEquals(10_000_000L, clock.onAudio(17_640, 44_100, 2))
    }

    @Test
    fun audioAfterVideoAdvancesBySampleCount() {
        val clock = NdiClock()
        assertEquals(10_000_000L, clock.onVideo(1_000_000_000L))
        assertEquals(10_000_000L, clock.onAudio(17_640, 44_100, 2))
        assertEquals(11_000_000L, clock.onAudio(17_640, 44_100, 2))
    }

    @Test
    fun captureToSendRejectsADifferentClock() {
        assertEquals(12_000L, StreamLatency.captureToSendUs(1_000_000_000L, 1_000_000_000L + 12_000_000L))
        assertNull(StreamLatency.captureToSendUs(0, 1_000L))
        assertNull(StreamLatency.captureToSendUs(1L, 60_000_000_000L))
    }
}
