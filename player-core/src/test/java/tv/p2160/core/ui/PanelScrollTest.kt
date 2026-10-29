package tv.p2160.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.p2160.core.engine.MediaReport
import tv.p2160.core.engine.PlayerUiState
import tv.p2160.core.engine.TrackOption
import tv.p2160.core.settings.ResizeMode
import tv.p2160.core.settings.Settings

/**
 * Панель открывается с нулевой прокрутки: переключатели и «Точно» на экране,
 * выбранная строка ниже сгиба не подтягивается. Повторное открытие тоже с нуля.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w400dp-h800dp")
class PanelScrollTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun audioOpensAtTheTopAndReopensThereAfterAScroll() {
        val tracks = List(6) { i ->
            TrackOption(
                type = 1,
                groupIndex = 0,
                trackIndex = i,
                label = if (i == 5) "AAC · моно" else "track $i",
                language = null,
                rawLabel = null,
                selected = i == 5,
                supported = true,
                external = false,
            )
        }
        assertOpensAtTop(
            panel = Panel.AUDIO,
            state = PlayerUiState(audioTracks = tracks),
            topText = "panel.night_mode",
            belowText = "AAC · моно",
        )
    }

    @Test
    fun speedStaysAtTheTopWhenTheFastPresetIsBelowTheFold() {
        assertOpensAtTop(
            panel = Panel.SPEED,
            state = PlayerUiState(speed = 4f),
            topText = "panel.fine",
            belowText = "4×",
            height = 160.dp,
        )
    }

    @Test
    fun everySidePanelTakesFocusOnOpen() {
        val entry = listOf(
            Panel.AUDIO to "panel.night_mode",
            Panel.SUBTITLES to "panel.subs_off",
            Panel.SPEED to "panel.fine",
            Panel.SLEEP to "panel.sleep_off",
            Panel.VIDEO to "panel.fit",
            Panel.CHAPTERS to "panel.mark_intro_start",
            Panel.INFO to "panel.stats_overlay",
        )
        var which by mutableIntStateOf(0)
        rule.setContent {
            key(entry[which].first) {
                PanelScrollHarness(
                    panel = entry[which].first,
                    state = PlayerUiState(),
                    resets = 0,
                    open = true,
                    height = 800.dp,
                )
            }
        }
        for (index in entry.indices) {
            rule.runOnIdle { which = index }
            rule.waitForIdle()
            assertFocusOn(entry[index].second)
        }
    }

    @Test
    fun subtitlesPanelDoesNotLetThePadLeaveToControls() {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp).height(800.dp)) {
                    Box(Modifier.focusable().testTag("behind"))
                    SidePanel(
                        panel = Panel.SUBTITLES,
                        state = PlayerUiState(),
                        settings = Settings(),
                        resizeMode = ResizeMode.FIT,
                        actions = idlePanelActions(),
                        scrollReset = PanelScrollReset(),
                        open = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        rule.waitForIdle()
        assertFocusOn("panel.subs_off")
        repeat(6) {
            rule.onNode(isFocused()).performKeyInput {
                keyDown(Key.DirectionDown)
                keyUp(Key.DirectionDown)
            }
            rule.waitForIdle()
        }
        val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        assertTrue(focused.isNotEmpty())
        assertTrue(focused.none { it.config.getOrNull(SemanticsProperties.TestTag) == "behind" })
    }

    @Test
    fun speedShowsTheStepperAndNormalPresetOnAShortLandscapeScreen() {
        // 360 dp экрана минус зазор под кнопки (64) и статус (24): панель 272 dp.
        // «Точно» и «Обычная» оба на экране, прокрутка нулевая.
        var open by mutableStateOf(true)
        var resets by mutableIntStateOf(0)
        rule.setContent {
            PanelScrollHarness(
                panel = Panel.SPEED,
                state = PlayerUiState(speed = 1f),
                resets = resets,
                open = open,
                height = 272.dp,
            )
        }
        rule.waitForIdle()
        rule.runOnIdle { resets++ }
        rule.waitForIdle()
        assertEquals(0f, scrollValue(), 0.5f)
        rule.onNodeWithText("panel.fine").assertIsDisplayed()
        rule.onNodeWithText("panel.speed_normal").assertIsDisplayed()
    }

    private fun assertOpensAtTop(
        panel: Panel,
        state: PlayerUiState,
        topText: String,
        belowText: String,
        height: Dp = 200.dp,
    ) {
        var open by mutableStateOf(true)
        var resets by mutableIntStateOf(0)
        rule.setContent {
            PanelScrollHarness(panel = panel, state = state, resets = resets, open = open, height = height)
        }
        rule.waitForIdle()
        val max = scrollMax()
        assertTrue("список должен быть длиннее окна, иначе проверка прокрутки пустая: max=$max", max > 0f)

        // Сброс на простое - тот, что бывает после закрытия. Уже нулевая прокрутка ничего не двигает.
        rule.runOnIdle { resets++ }
        rule.waitForIdle()
        assertAtTop(topText, belowText)

        rule.onNodeWithTag("panel-scroll").performTouchInput { swipeUp() }
        rule.waitForIdle()
        val moved = scrollValue()
        assertTrue("после жеста список должен уехать от верха, было $moved", moved > 0f)

        // Как после закрытия: панель за краем, сброс на следующем кадре, открытие снова сверху.
        rule.runOnIdle {
            open = false
            resets++
        }
        rule.waitForIdle()
        rule.runOnIdle { open = true }
        rule.waitForIdle()
        assertAtTop(topText, belowText)
    }

    private fun assertFocusOn(text: String) {
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        var current: SemanticsNode? = node
        var focused = false
        while (current != null) {
            if (current.config.getOrElse(SemanticsProperties.Focused) { false }) {
                focused = true
                break
            }
            current = current.parent
        }
        assertTrue("focus should be on $text", focused)
    }

    private fun assertAtTop(topText: String, belowText: String) {
        assertEquals(0f, scrollValue(), 0.5f)
        rule.onNodeWithText(topText).assertIsDisplayed()
        rule.onNodeWithText(belowText).assertIsNotDisplayed()
    }

    private fun scrollValue(): Float = scrollRange().value()

    private fun scrollMax(): Float = scrollRange().maxValue()

    private fun scrollRange() =
        rule.onNodeWithTag("panel-scroll").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
}

@Composable
private fun PanelScrollHarness(
    panel: Panel,
    state: PlayerUiState,
    resets: Int,
    open: Boolean,
    height: Dp,
) {
    val signal = remember { PanelScrollReset() }
    LaunchedEffect(resets) {
        if (resets > 0) signal.request()
    }
    MaterialTheme {
        Box(Modifier.width(360.dp).height(height)) {
            SidePanel(
                panel = panel,
                state = state,
                settings = Settings(),
                resizeMode = ResizeMode.FIT,
                actions = idlePanelActions(),
                scrollReset = signal,
                open = open,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun idlePanelActions() = PanelActions(
    onSelectTrack = { _ -> },
    onDisableSubtitles = {},
    onAddSubtitle = {},
    onSubtitleDelay = { _ -> },
    onSecondarySubtitle = { _ -> },
    onNightMode = { _ -> },
    onNightSchedule = { _, _, _ -> },
    onAudioDelay = { _ -> },
    onSleepTimer = { _ -> },
    onSleepAtEnd = {},
    report = { MediaReport(emptyList(), emptyList()) },
    onSubtitleSize = { _ -> },
    onSpeed = { _ -> },
    onAutoVideo = {},
    onResize = { _ -> },
    onChapter = { _ -> },
    onMarkIntroStart = {},
    onMarkIntroEnd = {},
    onMarkCredits = {},
    onClearMarks = {},
)
