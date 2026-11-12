package tv.p2160.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Буферизация не рисует дугу поверх залитого круга play.
 * Спиннер стоит вместо круга; в фокусе пульта вокруг него кольцо.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ControlButtonFaceTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun busyFocusIsASpinnerRingNotAFilledCircle() {
        assertEquals(ControlFace.Icon, controlFace(focused = false, busy = false))
        assertEquals(ControlFace.FilledIcon, controlFace(focused = true, busy = false))
        assertEquals(ControlFace.Spinner, controlFace(focused = false, busy = true))
        assertEquals(ControlFace.SpinnerRing, controlFace(focused = true, busy = true))
    }

    @Test
    fun bufferingDrawsSpinnerInsteadOfTheFilledPlayCircle() {
        val busy = mutableStateOf(true)
        val takeFocus = mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                val requester = remember { FocusRequester() }
                ControlButton(
                    icon = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    onClick = {},
                    busy = busy.value,
                    accent = Color(0xFFFFB020),
                    modifier = Modifier.testTag("play").focusRequester(requester),
                )
                LaunchedEffect(takeFocus.value) {
                    if (takeFocus.value) requester.requestFocus()
                }
            }
        }
        rule.waitForIdle()
        assertEquals("Play", description("play"))
        assertEquals(1, count("control-spinner"))
        assertEquals(0, count("control-fill"))
        assertEquals(0, count("control-focus-ring"))
        takeFocus.value = true
        rule.waitForIdle()
        assertEquals("Play", description("play"))
        assertEquals(1, count("control-spinner"))
        assertEquals(0, count("control-fill"))
        assertEquals(1, count("control-focus-ring"))
        busy.value = false
        rule.waitForIdle()
        assertEquals(0, count("control-spinner"))
        assertEquals(1, count("control-fill"))
        assertEquals(0, count("control-focus-ring"))
    }

    private fun count(tag: String): Int =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun description(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
}
