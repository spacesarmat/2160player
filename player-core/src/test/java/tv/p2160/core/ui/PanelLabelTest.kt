package tv.p2160.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Подписи звука и субтитров на узком телефоне (360 dp, как 1440 px при высокой плотности)
 * и при крупном шрифте не рвутся посреди слова.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PanelLabelTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun labelsDoNotBreakMidWordAt360dpAndLargeFont() {
        val panelDp = sidePanelWidthDp(360f)
        assertTrue(panelDp >= 320f)
        // Поля степпера: 16 dp у колонки и 8 dp у текста с каждой стороны.
        val labelDp = panelDp - 48f
        val layouts = mutableListOf<Pair<String, TextLayoutResult>>()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 4f, fontScale = 1.3f)) {
                MaterialTheme {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.bodyLarge.unbrokenWords()
                    val widthPx = with(LocalDensity.current) { labelDp.dp.roundToPx() }
                    val tightPx = with(LocalDensity.current) { 48.dp.roundToPx() }
                    SideEffect {
                        listOf("Задержка звука", "Размер").forEach { text ->
                            layouts += text to measurer.measure(
                                text,
                                style = style,
                                softWrap = true,
                                constraints = Constraints(maxWidth = widthPx),
                            )
                            layouts += "$text@tight" to measurer.measure(
                                text,
                                style = style,
                                softWrap = true,
                                constraints = Constraints(maxWidth = tightPx),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(4, layouts.size)
        layouts.forEach { (name, layout) ->
            val text = layout.layoutInput.text.toString()
            for (line in 0 until layout.lineCount) {
                val start = layout.getLineStart(line)
                if (start > 0) {
                    assertTrue(
                        "$name broke mid-word at $start ('$text')",
                        text[start - 1].isWhitespace(),
                    )
                }
            }
        }
        val wide = layouts.filter { !it.first.endsWith("@tight") }
        assertEquals(1, wide.first { it.first == "Размер" }.second.lineCount)
        assertEquals(1, wide.first { it.first == "Задержка звука" }.second.lineCount)
    }
}
