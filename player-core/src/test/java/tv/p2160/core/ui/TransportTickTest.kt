package tv.p2160.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
