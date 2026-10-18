package tv.p2160.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.p2160.core.engine.PlayerUiState

/**
 * Тик позиции не меняет ширину подписи и не измеряет родителя полосы заново.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransportTickTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun positionTickKeepsTheReadoutWidthAndSkipsParentMeasure() {
        val clock = TransportClock(0L, 0L)
        val measures = intArrayOf(0)
        rule.setContent {
            MaterialTheme {
                Box(
                    Modifier.layout { measurable, constraints ->
                        measures[0]++
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    FixedTickText(
                        clock = clock,
                        sample = TIME_SAMPLE,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textKey = Unit,
                        label = { formatTime(it.positionMs.longValue) },
                        modifier = Modifier.testTag("readout"),
                    )
                }
            }
        }
        rule.waitForIdle()
        val width = rule.onNodeWithTag("readout").getUnclippedBoundsInRoot().let { it.right - it.left }
        val before = measures[0]
        clock.publish(3_723_000L, 0L)
        rule.waitForIdle()
        val after = rule.onNodeWithTag("readout").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertEquals(width.value, after.value, 0.05f)
        assertEquals(before, measures[0])
    }

    @Test
    fun positionTickDoesNotRemeasureTheSeekBar() {
        val clock = TransportClock(0L, 1_000L)
        val measures = intArrayOf(0)
        rule.setContent {
            MaterialTheme {
                Box(
                    Modifier.width(320.dp).layout { measurable, constraints ->
                        measures[0]++
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    SeekBar(
                        clock = clock,
                        durationMs = 60_000L,
                        stepMs = 10_000L,
                        onSeek = {},
                        onInteraction = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        val before = measures[0]
        clock.publish(15_000L, 40_000L)
        rule.waitForIdle()
        assertEquals(before, measures[0])
    }

    @Test
    fun remainingTimeEndsWithTheSeekBarForShortAndHourDurations() {
        val short = 5 * 60_000L
        val hour = 60 * 60_000L
        val duration = mutableStateOf(short)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(400.dp)) {
                    TransportReadout(
                        clock = TransportClock(0L, 0L),
                        state = PlayerUiState(durationMs = duration.value, isBuffering = false),
                        theme = PlayerThemes.Cinema,
                        stepMs = 10_000L,
                        onSeek = {},
                        onInteraction = {},
                        onScrub = {},
                        onGoTo = {},
                    )
                }
            }
        }
        fun widthFlushWithTheBar(): Float {
            rule.waitForIdle()
            val bar = rule.onNodeWithTag("seek-bar").getUnclippedBoundsInRoot()
            val elapsed = rule.onNodeWithTag("time-elapsed", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val remaining = rule.onNodeWithTag("time-remaining", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals(bar.right.value, remaining.right.value, 1f)
            assertEquals(bar.left.value, elapsed.left.value, 1f)
            return (remaining.right - remaining.left).value
        }
        val shortWidth = widthFlushWithTheBar()
        assertEquals("−5:00 / 5:00", description("time-remaining"))
        rule.runOnIdle { duration.value = hour }
        val hourWidth = widthFlushWithTheBar()
        assertEquals("−1:00:00 / 1:00:00", description("time-remaining"))
        assertTrue(hourWidth > shortWidth)
    }

    @Test
    fun dragShowsTheScrubPositionWithoutRelayout() {
        val clock = TransportClock(0L, 0L)
        val measures = intArrayOf(0)
        rule.setContent {
            MaterialTheme {
                Column(
                    Modifier.width(400.dp).layout { measurable, constraints ->
                        measures[0]++
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    TransportReadout(
                        clock = clock,
                        state = PlayerUiState(durationMs = 60_000L, isBuffering = false),
                        theme = PlayerThemes.Cinema,
                        stepMs = 10_000L,
                        onSeek = {},
                        onInteraction = {},
                        onScrub = {},
                        onGoTo = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("seek-bar").performTouchInput {
            down(Offset(1f, height / 2f))
            moveTo(Offset(width * 0.25f, height / 2f))
        }
        rule.waitForIdle()
        val afterFirstMove = measures[0]
        rule.onNodeWithTag("seek-bar").performTouchInput {
            moveTo(Offset(width * 0.5f, height / 2f))
        }
        rule.waitForIdle()
        assertEquals(afterFirstMove, measures[0])
        assertEquals("0:30", description("time-elapsed"))
        assertEquals("−0:30 / 1:00", description("time-remaining"))
        rule.onNodeWithTag("seek-bar").performTouchInput { up() }
    }

    private fun description(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode("readout").config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
}
