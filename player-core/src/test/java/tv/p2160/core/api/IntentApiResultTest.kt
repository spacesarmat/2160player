package tv.p2160.core.api

import android.app.Activity
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class IntentApiResultTest {
    @Test
    fun buildResultKeepsMxAndAddsWatchedPosition() {
        val uri = Uri.parse("file:///film.mkv")
        val intent = IntentApi.buildResult(PlaybackResult(uri, 1_394_000, 5_400_000, true))
        assertEquals(1_394_000, intent.getIntExtra(IntentApi.RESULT_POSITION, -1))
        assertEquals(IntentApi.END_BY_COMPLETION, intent.getStringExtra(IntentApi.RESULT_END_BY))
        assertTrue(intent.getBooleanExtra(IntentApi.EXTRA_WATCHED, false))
        assertEquals(1_394_000L, intent.getLongExtra(IntentApi.EXTRA_RESULT_POSITION, -1))
        val parsed = IntentApi.parseResult(Activity.RESULT_OK, intent)
        assertEquals(1_394_000L, parsed?.positionMs)
        assertTrue(parsed?.completed == true)
        assertNull(intent.data)
        assertNull(parsed?.uri)
    }

    @Test
    fun httpResultKeepsTheDataUri() {
        val uri = Uri.parse("http://192.168.1.191:8090/stream/episode.mkv")
        val intent = IntentApi.buildResult(PlaybackResult(uri, 18_400, 40_000, false))
        assertEquals(uri, intent.data)
        assertEquals(IntentApi.END_BY_USER, intent.getStringExtra(IntentApi.RESULT_END_BY))
        assertFalse(intent.getBooleanExtra(IntentApi.EXTRA_WATCHED, true))
    }

    @Test
    fun longPositionSurvivesWhenMxIntOverflows() {
        val intent = IntentApi.buildResult(PlaybackResult(Uri.parse("file:///long.mkv"), 3_000_000_000L, 4_000_000_000L, false))
        val parsed = IntentApi.parseResult(Activity.RESULT_OK, intent)
        assertEquals(3_000_000_000L, parsed?.positionMs)
        assertEquals(4_000_000_000L, parsed?.durationMs)
        assertFalse(parsed?.completed == true)
    }

    @Test
    fun parseResultStillUnderstandsMxEndBy() {
        val intent = Intent().apply {
            putExtra(IntentApi.RESULT_POSITION, 10)
            putExtra(IntentApi.RESULT_DURATION, 100)
            putExtra(IntentApi.RESULT_END_BY, IntentApi.END_BY_COMPLETION)
        }
        val parsed = IntentApi.parseResult(Activity.RESULT_OK, intent)
        assertEquals(10L, parsed?.positionMs)
        assertTrue(parsed?.completed == true)
    }
}
