package tv.p2160.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.core.settings.NightSchedule
import tv.p2160.core.settings.Settings

class NightScheduleTest {
    private fun m(h: Int, min: Int = 0) = h * 60 + min

    @Test
    fun overMidnight() {
        val start = m(23)
        val end = m(10)
        assertTrue(NightSchedule.contains(start, end, m(23)))
        assertTrue(NightSchedule.contains(start, end, m(2, 30)))
        assertTrue(NightSchedule.contains(start, end, m(9, 59)))
        assertFalse(NightSchedule.contains(start, end, m(10)))
        assertFalse(NightSchedule.contains(start, end, m(15)))
        assertFalse(NightSchedule.contains(start, end, m(22, 59)))
    }

    @Test
    fun sameDayAndEmpty() {
        assertTrue(NightSchedule.contains(m(13), m(15), m(14)))
        assertFalse(NightSchedule.contains(m(13), m(15), m(15)))
        assertFalse(NightSchedule.contains(m(8), m(8), m(8)))
    }

    @Test
    fun manualOrSchedule() {
        val auto = Settings(nightAuto = true)
        assertTrue(auto.nightModeAt(m(0)))
        assertFalse(auto.nightModeAt(m(12)))
        assertTrue(Settings(nightMode = true).nightModeAt(m(12)))
        assertFalse(Settings().nightModeAt(m(0)))
        assertTrue(NightSchedule.format(m(9, 30)) == "09:30")
    }
}
