package tv.p2160.core.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionLogTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ActionLog.setEnabled(context, false)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ActionLog.setEnabled(context, false)
        File(context.filesDir, "diagnostics").deleteRecursively()
        ShadowLog.clear()
    }

    @Test
    fun disabledModeDoesNotWriteLogcat() {
        ActionLog.event("player", "open item=0 uri=https://example.test/video?token=private")

        assertTrue(ShadowLog.getLogsForTag(ActionLog.LOG_TAG).isEmpty())
    }

    @Test
    fun enabledModeWritesLogcat() {
        ActionLog.setEnabled(context, true)
        ShadowLog.clear()

        ActionLog.event("player", "open item=0")

        val logs = ShadowLog.getLogsForTag(ActionLog.LOG_TAG)
        assertEquals(1, logs.size)
        assertTrue(logs.single().msg.contains("open item=0"))
    }
}
