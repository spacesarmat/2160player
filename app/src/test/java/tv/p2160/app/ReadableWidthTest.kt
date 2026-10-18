package tv.p2160.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadableWidthTest {
    @Test
    fun widePhoneUsesSixtyPercentNotSevenTwentyDp() {
        // Galaxy S21 Ultra лёжа: 3200×1440 px, плотность 600 → 853×384 dp. 720 dp здесь почти весь экран.
        assertEquals(511.8f, readableContentMaxDp(853f, 384f), 0.1f)
    }

    @Test
    fun televisionKeepsACenteredColumn() {
        assertEquals(576f, readableContentMaxDp(960f, 540f), 0.1f)
        assertEquals(720f, readableContentMaxDp(1920f, 1080f), 0.1f)
    }

    @Test
    fun portraitStaysFullWidth() {
        assertEquals(384f, readableContentMaxDp(384f, 853f), 0.1f)
    }

    @Test
    fun emptyWidthStaysEmpty() {
        assertEquals(0f, readableContentMaxDp(0f, 0f), 0.01f)
    }
}
