package tv.p2160.core.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelBackTest {
    @Test
    fun backClosesAnOpenSidePanelBeforeAnythingElse() {
        assertTrue(backClosesOpenPanel(panelOpen = true, blockingOverlay = false))
        assertFalse(backClosesOpenPanel(panelOpen = false, blockingOverlay = false))
        assertFalse(backClosesOpenPanel(panelOpen = true, blockingOverlay = true))
    }
}
