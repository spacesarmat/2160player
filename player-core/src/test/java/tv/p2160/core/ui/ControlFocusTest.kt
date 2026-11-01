package tv.p2160.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlFocusTest {
    @Test
    fun bufferingDoesNotPullFocusBackToPlay() {
        assertFalse(
            shouldMoveFocusToPlay(
                controlsVisible = true,
                panelOpen = false,
                canFocusControls = true,
                controlsHaveFocus = true,
                controlsJustShown = false,
                panelJustClosed = false,
            ),
        )
        // Фокус уже на «Аудио» или «Субтитры». Смена buffering/ready не повод возвращать его в центр.
        assertFalse(
            shouldMoveFocusToPlay(
                controlsVisible = true,
                panelOpen = false,
                canFocusControls = true,
                controlsHaveFocus = false,
                controlsJustShown = false,
                panelJustClosed = false,
            ),
        )
    }

    @Test
    fun playFocusOnlyOnFirstShowOrPanelClose() {
        assertTrue(
            shouldMoveFocusToPlay(
                controlsVisible = true,
                panelOpen = false,
                canFocusControls = true,
                controlsHaveFocus = false,
                controlsJustShown = true,
                panelJustClosed = false,
            ),
        )
        assertTrue(
            shouldMoveFocusToPlay(
                controlsVisible = true,
                panelOpen = false,
                canFocusControls = true,
                controlsHaveFocus = false,
                controlsJustShown = false,
                panelJustClosed = true,
            ),
        )
        assertFalse(
            shouldMoveFocusToPlay(
                controlsVisible = true,
                panelOpen = false,
                canFocusControls = true,
                controlsHaveFocus = true,
                controlsJustShown = true,
                panelJustClosed = true,
            ),
        )
        assertFalse(
            shouldMoveFocusToPlay(
                controlsVisible = false,
                panelOpen = false,
                canFocusControls = true,
                controlsHaveFocus = false,
                controlsJustShown = true,
                panelJustClosed = false,
            ),
        )
        assertFalse(
            shouldMoveFocusToPlay(
                controlsVisible = true,
                panelOpen = true,
                canFocusControls = true,
                controlsHaveFocus = false,
                controlsJustShown = false,
                panelJustClosed = false,
            ),
        )
    }
}
