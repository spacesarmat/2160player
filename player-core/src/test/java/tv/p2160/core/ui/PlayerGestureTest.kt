package tv.p2160.core.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Касания при видимых кнопках и отступ полосы в том же кадре, что и открытие панели.
 * Хост повторяет порядок слоёв [PlayerScreen]: жесты, затем [ControlsFade], затем панель.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1000dp-h600dp-land")
class PlayerGestureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun tapOutsideAnOpenPanelClosesItAndBackDoesToo() {
        var backs = 0
        var dispatcher: OnBackPressedDispatcher? = null
        rule.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            GestureHarness(onBack = { backs++ })
        }
        rule.onNodeWithTag("open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("panel").assertExists()

        // Back закрывает панель и не уходит из плеера.
        rule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("panel").assertDoesNotExist()
        assertEquals(0, backs)

        rule.onNodeWithTag("open").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("panel").assertExists()

        // Нажатие по самой панели её не закрывает.
        rule.onNodeWithTag("panel").performClick()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("panel").assertExists()

        rule.onNodeWithTag("root").performTouchInput {
            click(Offset(this.width * 0.2f, this.height * 0.45f))
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("panel").assertDoesNotExist()
        assertEquals(0, backs)

        // Панели нет — Back выходит.
        rule.runOnIdle { checkNotNull(dispatcher).onBackPressed() }
        assertEquals(1, backs)
    }

    @Test
    fun tapOnVideoHidesVisibleControls() {
        rule.setContent { GestureHarness() }
        rule.onNodeWithTag("controls").assertTextEquals("shown")
        rule.onNodeWithTag("root").performTouchInput {
            click(Offset(this.width * 0.5f, this.height * 0.4f))
        }
        // Одиночный тап ждёт окно двойного, как в плеере.
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("controls").assertTextEquals("hidden")
    }

    @Test
    fun doubleTapOnTheRightSeeksForwardWhileControlsAreVisible() {
        val seeks = mutableListOf<Long>()
        rule.setContent {
            GestureHarness(stepMs = 10_000L, onSeek = { seeks += it })
        }
        rule.onNodeWithTag("controls").assertTextEquals("shown")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("root").performTouchInput {
            val point = Offset(this.width * 0.85f, this.height * 0.4f)
            click(point)
            advanceEventTime(40)
            click(point)
        }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(listOf(10_000L), seeks)
        rule.onNodeWithTag("controls").assertTextEquals("shown")
    }

    @Test
    fun transportShrinksOnTheFrameThePanelOpensAndRestoresWhenItCloses() {
        rule.setContent { GestureHarness() }
        rule.waitForIdle()
        val full = rule.onNodeWithTag("transport").getUnclippedBoundsInRoot().let { it.right - it.left }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("open").performClick()
        // Жест садится в один кадр, открытие — в следующий. В кадре, где панель уже есть,
        // полоса уже узкая: отступ не ждёт отдельной пересборки.
        var narrow = full
        var opened = false
        for (frame in 0 until 3) {
            rule.mainClock.advanceTimeByFrame()
            val panelUp = rule.onAllNodesWithTag("panel").fetchSemanticsNodes().isNotEmpty()
            val width = rule.onNodeWithTag("transport").getUnclippedBoundsInRoot().let { it.right - it.left }
            if (!panelUp) {
                assertEquals("кадр $frame, панель ещё закрыта", full.value, width.value, 0.5f)
            } else {
                narrow = width
                opened = true
                break
            }
        }
        assertTrue(opened)
        assertTrue(
            "полоса должна сузиться в том же кадре, что открылась панель: было $full, стало $narrow",
            narrow < full - 100.dp,
        )

        rule.mainClock.autoAdvance = true
        rule.onNodeWithTag("root").performTouchInput {
            click(Offset(this.width * 0.2f, this.height * 0.45f))
        }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("panel").assertDoesNotExist()
        val restored = rule.onNodeWithTag("transport").getUnclippedBoundsInRoot().let { it.right - it.left }
        assertEquals(full.value, restored.value, 0.5f)
    }
}

@Composable
private fun GestureHarness(
    stepMs: Long = 10_000L,
    onSeek: (Long) -> Unit = {},
    onPlayPause: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    BackHandler {
        if (panel != null) panel = null else onBack()
    }
    MaterialTheme {
        Box(Modifier.fillMaxSize().testTag("root")) {
            TapSeekLayer(
                stepMs = stepMs,
                onSingleTap = {
                    if (panel != null) panel = null
                    else controlsVisible = !controlsVisible
                },
                onCenterDoubleTap = onPlayPause,
                onSeek = onSeek,
                onScrubStart = {},
                onScrub = {},
                onScrubEnd = {},
                onSpeedBoost = {},
            )
            ControlsFade(visible = controlsVisible, panelOpen = panel != null) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.align(Alignment.TopStart).size(48.dp).testTag("open")
                            .clickable { panel = Panel.AUDIO },
                    )
                    val end = transportEndInsetDp(
                        panel != null,
                        LocalConfiguration.current.screenWidthDp.toFloat(),
                    ).dp
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(end = end)) {
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).testTag("transport")
                                .background(Color.White.copy(alpha = 0.2f)),
                        )
                    }
                }
            }
            if (panel != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).width(220.dp).fillMaxHeight().testTag("panel")
                        .background(Color.DarkGray)
                        .clickable { },
                )
            }
            Text(
                if (controlsVisible) "shown" else "hidden",
                modifier = Modifier.align(Alignment.TopCenter).size(1.dp).testTag("controls"),
                color = Color.Transparent,
            )
        }
    }
}
