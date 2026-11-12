package tv.p2160.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.p2160.desktop.media.assignSurface
import tv.p2160.desktop.media.nextTrackId
import tv.p2160.desktop.player.MediaTrack

class TrackAndSurfaceTest {
    @Test
    fun surfaceKeepsBothDimensions() {
        assertEquals(640 to 360, assignSurface(640, 360))
        assertEquals(0 to 1080, assignSurface(-1, 5000))
    }

    @Test
    fun cycleGoesToOffAfterTheSelectedTrack() {
        assertNull(nextTrackId(emptyList()))
        val only = listOf(track(1, selected = true))
        assertNull(nextTrackId(only))
        val two = listOf(track(1, selected = true), track(2, selected = false))
        assertEquals(2, nextTrackId(two))
        val secondSelected = listOf(track(1, selected = false), track(2, selected = true))
        assertNull(nextTrackId(secondSelected))
    }

    private fun track(id: Int, selected: Boolean) = MediaTrack(id, "audio", "t$id", "ru", selected)
}
