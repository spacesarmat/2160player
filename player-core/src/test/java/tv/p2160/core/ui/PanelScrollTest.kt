package tv.p2160.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
    fun speedOpensAtTheTopAndReopensThereAfterAScroll() {
        assertOpensAtTop(
            panel = Panel.SPEED,
            state = PlayerUiState(speed = 1f),
            topText = "panel.fine",
            belowText = "panel.speed_normal",
        )
    }

    private fun assertOpensAtTop(panel: Panel, state: PlayerUiState, topText: String, belowText: String) {
        var opens by mutableIntStateOf(0)
        rule.setContent {
            PanelScrollHarness(panel = panel, state = state, opens = opens)
        }
        rule.waitForIdle()
        val max = scrollMax()
        assertTrue("список должен быть длиннее окна, иначе проверка прокрутки пустая: max=$max", max > 0f)

        rule.runOnIdle { opens++ }
        rule.waitForIdle()
        assertAtTop(topText, belowText)

        rule.onNodeWithTag("panel-scroll").performTouchInput { swipeUp() }
        rule.waitForIdle()
        val moved = scrollValue()
        assertTrue("после жеста список должен уехать от верха, было $moved", moved > 0f)

        rule.runOnIdle { opens++ }
        rule.waitForIdle()
        assertAtTop(topText, belowText)
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
private fun PanelScrollHarness(panel: Panel, state: PlayerUiState, opens: Int) {
    val signal = remember { PanelOpenSignal() }
    LaunchedEffect(opens) {
        if (opens > 0) signal.prepare()
    }
    MaterialTheme {
        // Окно короче списка: выбранная строка ниже сгиба, верхние переключатели должны остаться.
        Box(Modifier.width(360.dp).height(200.dp)) {
            SidePanel(
                panel = panel,
                state = state,
                settings = Settings(),
                resizeMode = ResizeMode.FIT,
                actions = idlePanelActions(),
                openSignal = signal,
                open = true,
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
