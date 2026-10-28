package tv.p2160.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.p2160.core.api.Chapter
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
                        samples = listOf(TIME_SAMPLE),
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
    fun unknownDurationShowsPlaceholderInsteadOfLive() {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.width(400.dp)) {
                    TransportReadout(
                        clock = TransportClock(0L, 0L),
                        state = PlayerUiState(durationMs = 0L, isBuffering = false, isLive = false),
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
        assertEquals(UNKNOWN_TIME, description("time-elapsed"))
        assertEquals(0, rule.onAllNodesWithTag("time-remaining", useUnmergedTree = true).fetchSemanticsNodes().size)
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

    @Test
    fun bufferingElapsedKeepsTheLastDigitAndDoesNotRelayout() {
        val clock = TransportClock(209_000L, 0L)
        val measures = intArrayOf(0)
        val probe = intArrayOf(0)
        rule.setContent {
            MaterialTheme {
                val measurer = rememberTextMeasurer()
                val style = MaterialTheme.typography.bodyMedium.merge(
                    TextStyle(fontFeatureSettings = "tnum", color = Color.White),
                )
                SideEffect {
                    val layout = measurer.measure(
                        "3:29",
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        constraints = Constraints(maxWidth = Constraints.Infinity),
                    )
                    probe[0] = textWidthPx(layout)
                }
                Box(
                    Modifier.layout { measurable, constraints ->
                        measures[0]++
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    },
                ) {
                    FixedTickText(
                        clock = clock,
                        samples = elapsedBoxSamples(0L),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textKey = 0L,
                        label = { transportElapsedText(durationKnown = false, it.shownMs()) },
                        modifier = Modifier.testTag("readout"),
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals("3:29", description("readout"))
        val width = rule.onNodeWithTag("readout").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("box ${width.value} dp is narrower than 3:29 (${probe[0]} px)", width.value * rule.density.density >= probe[0])
        val before = measures[0]
        clock.publish(210_000L, 0L)
        rule.waitForIdle()
        assertEquals("3:30", description("readout"))
        val after = rule.onNodeWithTag("readout").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertEquals(width.value, after.value, 0.05f)
        assertEquals(before, measures[0])
    }

    @Test
    fun reservedWidthCoversEveryElapsedAndRemainingString() {
        val elapsed = listOf(UNKNOWN_TIME, "3:29", "9:59", "10:00", "59:59", "1:23:45", "12:34:56")
        val remaining = listOf(
            5 * 60_000L to listOf("−3:29 / 5:00", "−0:00 / 5:00", "−9:59 / 5:00"),
            10 * 60_000L to listOf("−10:00 / 10:00", "−59:59 / 59:59"),
            60 * 60_000L to listOf("−59:59 / 1:00:00", "−1:00:00 / 1:00:00", "−0:01 / 1:00:00"),
        )
        val scales = listOf(1f to 2f, 1.3f to 2f, 1f to 4f, 1.3f to 4f)
        val widths = mutableMapOf<String, Int>()
        rule.setContent {
            scales.forEach { (font, dens) ->
                CompositionLocalProvider(LocalDensity provides Density(density = dens, fontScale = font)) {
                    MaterialTheme {
                        val measurer = rememberTextMeasurer()
                        val style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum"))
                        SideEffect {
                            val key = "$font@$dens"
                            fun widthOf(text: String): Int {
                                val layout = measurer.measure(
                                    text,
                                    style = style,
                                    maxLines = 1,
                                    softWrap = false,
                                    constraints = Constraints(maxWidth = Constraints.Infinity),
                                )
                                return textWidthPx(layout)
                            }
                            val box = elapsedBoxSamples(0L).maxOf { widthOf(it) }
                            widths["$key|elapsed"] = box
                            elapsed.forEach { text -> widths["$key|e|$text"] = widthOf(text) }
                            remaining.forEach { (duration, texts) ->
                                val reserved = widthOf(remainingTimeSample(duration))
                                widths["$key|r|$duration"] = reserved
                                texts.forEach { text -> widths["$key|t|$text"] = widthOf(text) }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        scales.forEach { (font, dens) ->
            val key = "$font@$dens"
            val box = widths.getValue("$key|elapsed")
            elapsed.forEach { text ->
                val width = widths.getValue("$key|e|$text")
                assertTrue("$text at font $font density $dens is $width px, box $box", box >= width)
            }
            remaining.forEach { (duration, texts) ->
                val reserved = widths.getValue("$key|r|$duration")
                texts.forEach { text ->
                    val width = widths.getValue("$key|t|$text")
                    assertTrue("$text at font $font density $dens is $width px, reserved $reserved", reserved >= width)
                }
            }
        }
    }

    @Test
    fun remainingStaysFlushWithTheBarWithAndWithoutChapters() {
        val duration = 9_391_000L
        val clock = TransportClock(236_000L, 0L)
        val width = mutableStateOf(800.dp)
        val fontScale = mutableStateOf(1f)
        val chapters = mutableStateOf<List<Chapter>>(emptyList())
        val measures = intArrayOf(0)
        val titleWidthPx = intArrayOf(0)
        val longTitle = "Очень длинное название главы ".repeat(16)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density = base.density, fontScale = fontScale.value),
            ) {
                MaterialTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.bodyMedium
                    val shown = chapters.value.getOrNull(0)?.title
                    SideEffect {
                        if (shown == null) return@SideEffect
                        val layout = measurer.measure(
                            "  ·  $shown",
                            style = style,
                            maxLines = 1,
                            softWrap = false,
                            constraints = Constraints(maxWidth = Constraints.Infinity),
                        )
                        titleWidthPx[0] = textWidthPx(layout)
                    }
                    Column(
                        Modifier.width(width.value).layout { measurable, constraints ->
                            measures[0]++
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        },
                    ) {
                        TransportReadout(
                            clock = clock,
                            state = PlayerUiState(
                                durationMs = duration,
                                isBuffering = false,
                                chapters = chapters.value,
                                chapterIndex = if (chapters.value.isEmpty()) -1 else 0,
                            ),
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
        }
        fun assertFlush(label: String, chapter: Boolean) {
            rule.waitForIdle()
            val bar = rule.onNodeWithTag("seek-bar").getUnclippedBoundsInRoot()
            val elapsed = rule.onNodeWithTag("time-elapsed", useUnmergedTree = true).getUnclippedBoundsInRoot()
            val remaining = rule.onNodeWithTag("time-remaining", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertEquals("$label remaining", bar.right.value, remaining.right.value, 1f)
            assertEquals("$label elapsed", bar.left.value, elapsed.left.value, 1f)
            assertEquals("3:56", description("time-elapsed"))
            assertEquals("−2:32:35 / 2:36:31", description("time-remaining"))
            if (!chapter) {
                assertEquals(0, rule.onAllNodesWithTag("chapter-title", useUnmergedTree = true).fetchSemanticsNodes().size)
                return
            }
            val title = rule.onNodeWithTag("chapter-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$label chapter overlaps elapsed", title.left.value >= elapsed.right.value - 1f)
            assertTrue("$label chapter overlaps remaining", title.right.value <= remaining.left.value + 1f)
            val titlePx = (title.right - title.left).value * rule.density.density
            assertTrue(
                "$label title ${titlePx}px is wider than the text ${titleWidthPx[0]}px",
                titlePx <= titleWidthPx[0] + 1f,
            )
        }
        fun remainingWidth(): Float {
            val bounds = rule.onNodeWithTag("time-remaining", useUnmergedTree = true).getUnclippedBoundsInRoot()
            return (bounds.right - bounds.left).value
        }
        assertFlush("landscape, no chapter", chapter = false)
        val landscapeRemaining = remainingWidth()
        chapters.value = listOf(Chapter("Chapter 01", 0L, duration))
        assertFlush("landscape, short chapter", chapter = true)
        val shortTitle = rule.onNodeWithTag("chapter-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val shortRemaining = rule.onNodeWithTag("time-remaining", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(landscapeRemaining, (shortRemaining.right - shortRemaining.left).value, 0.5f)
        assertTrue(shortTitle.right.value < shortRemaining.left.value - 8f)
        chapters.value = listOf(Chapter(longTitle, 0L, duration))
        assertFlush("landscape, long chapter", chapter = true)
        val longNode = rule.onNodeWithTag("chapter-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(
            "landscape title was not ellipsized (${(longNode.right - longNode.left).value} dp vs ${titleWidthPx[0]} px)",
            (longNode.right - longNode.left).value * rule.density.density < titleWidthPx[0] - 1f,
        )
        assertEquals(landscapeRemaining, remainingWidth(), 0.5f)
        width.value = 360.dp
        fontScale.value = 1.3f
        chapters.value = emptyList()
        assertFlush("portrait, no chapter, font 1.3", chapter = false)
        val portraitRemaining = remainingWidth()
        chapters.value = listOf(Chapter(longTitle, 0L, duration))
        assertFlush("portrait, long chapter, font 1.3", chapter = true)
        val narrow = rule.onNodeWithTag("chapter-title", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue((narrow.right - narrow.left).value * rule.density.density < titleWidthPx[0] - 1f)
        assertEquals(portraitRemaining, remainingWidth(), 0.5f)
        val before = measures[0]
        clock.publish(237_000L, 0L)
        rule.waitForIdle()
        assertEquals(before, measures[0])
        assertEquals("3:57", description("time-elapsed"))
    }

    private fun description(tag: String): String =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode("readout").config
            .getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            .joinToString()
}
