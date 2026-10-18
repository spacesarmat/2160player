package tv.p2160.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.p2160.app.camera.CameraLabels.Lens
import java.util.Locale

class CameraLabelsTest {
    private val ru = Locale("ru", "RU")

    private fun labels(vararg lenses: Lens): List<String> =
        CameraLabels.names(lenses.toList()).map { it.text(::ru, ru) }

    @Test
    fun singleBackAndFrontKeepFacingNames() {
        assertEquals(
            listOf("Задняя", "Фронтальная"),
            labels(Lens("0", CameraLabels.BACK, 6.4f), Lens("1", CameraLabels.FRONT, 3.3f)),
        )
    }

    @Test
    fun backLensesUseFocalLengthAgainstTheMainCamera() {
        // Эквивалент 35 мм: основная 27, сверхширокая 14,4 (0,5×), теле 81 (3×) и 270 (10×).
        assertEquals(
            listOf("Основная", "Сверхширокая", "Телевик 3×", "Телевик 10×", "Фронтальная"),
            labels(
                Lens("0", CameraLabels.BACK, 6f, 8f),
                Lens("2", CameraLabels.BACK, 2f, 5f),
                Lens("3", CameraLabels.BACK, 18f, 8f),
                Lens("4", CameraLabels.BACK, 60f, 8f),
                Lens("1", CameraLabels.FRONT, 3.2f, 4.27f),
            ),
        )
    }

    @Test
    fun identicalFrontCamerasAreNumbered() {
        assertEquals(
            listOf("Фронтальная", "Фронтальная 2"),
            labels(Lens("1", CameraLabels.FRONT, 3.3f), Lens("20", CameraLabels.FRONT, 3.3f)),
        )
    }

    @Test
    fun secondUltraKeepsItsOwnZoom() {
        assertEquals(
            listOf("Основная", "Сверхширокая 0,6×", "Сверхширокая 0,4×"),
            labels(
                Lens("0", CameraLabels.BACK, 6.4f),
                Lens("2", CameraLabels.BACK, 3.84f),
                Lens("5", CameraLabels.BACK, 2.56f),
            ),
        )
    }

    @Test
    fun focalRatioWithoutSensorStillSeparatesTele() {
        assertEquals(
            listOf("Основная", "Сверхширокая", "Телевик 3×"),
            labels(
                Lens("0", CameraLabels.BACK, 6.4f),
                Lens("2", CameraLabels.BACK, 2.2f),
                Lens("3", CameraLabels.BACK, 19.2f),
            ),
        )
    }

    @Test
    fun logicalCameraIsDroppedWhenItsPhysicalIdsAreListed() {
        assertEquals(
            listOf("Сверхширокая", "Основная"),
            labels(
                Lens("0", CameraLabels.BACK, 6f, 8f, logicalDuplicate = true),
                Lens("2", CameraLabels.BACK, 2f, 5f),
                Lens("3", CameraLabels.BACK, 6f, 8f),
            ),
        )
    }

    @Test
    fun sameZoomFallsBackToANumber() {
        assertEquals(
            listOf("Основная", "Телевик 3×", "Телевик 3× 2"),
            labels(
                Lens("0", CameraLabels.BACK, 6f),
                Lens("3", CameraLabels.BACK, 18f),
                Lens("4", CameraLabels.BACK, 18.2f),
            ),
        )
    }

    @Test
    fun frontUltraDoesNotReuseTheBackWordAlone() {
        assertEquals(
            listOf("Основная", "Сверхширокая", "Фронтальная", "Фронтальная сверхширокая"),
            labels(
                Lens("0", CameraLabels.BACK, 6f, 8f),
                Lens("2", CameraLabels.BACK, 2f, 5f),
                Lens("1", CameraLabels.FRONT, 3.2f, 4.4f),
                Lens("6", CameraLabels.FRONT, 1.6f, 4f),
            ),
        )
    }

    @Test
    fun secondFrontIsNamedByHowItDiffers() {
        // 26 мм и 23 мм экв.: уже не «Фронтальная 2», а чем объектив шире.
        assertEquals(
            listOf("Фронтальная", "Фронтальная широкая"),
            labels(
                Lens("1", CameraLabels.FRONT, 3.2f, 4.4f),
                Lens("2", CameraLabels.FRONT, 2.8f, 4.4f),
            ),
        )
        assertEquals(
            listOf("Фронтальная", "Фронтальная телевик 2×"),
            labels(
                Lens("1", CameraLabels.FRONT, 3.2f, 4.4f),
                Lens("3", CameraLabels.FRONT, 6.4f, 4.4f),
            ),
        )
    }

    @Test
    fun externalStaysExternal() {
        assertEquals(listOf("Внешняя"), labels(Lens("8", CameraLabels.EXTERNAL, 4f)))
    }

    @Test
    fun pickFocalPrefersTheStandardWideNotTheShortest() {
        val focals = floatArrayOf(2.2f, 18f, 6.4f)
        assertEquals(6.4f, CameraLabels.pickFocal(focals, sensorWidthMm = 8f), 0.01f)
        assertEquals(6.4f, CameraLabels.pickFocal(focals, sensorWidthMm = null), 0.01f)
        assertEquals(4f, CameraLabels.pickFocal(floatArrayOf(4f), null), 0.01f)
        assertEquals(0f, CameraLabels.pickFocal(null, null), 0.01f)
    }

    private fun ru(key: String): String = when (key) {
        CameraLabels.KEY_BACK -> "Задняя"
        CameraLabels.KEY_MAIN -> "Основная"
        CameraLabels.KEY_FRONT -> "Фронтальная"
        CameraLabels.KEY_EXTERNAL -> "Внешняя"
        CameraLabels.KEY_WIDE -> "Широкая"
        CameraLabels.KEY_ULTRA -> "Сверхширокая"
        CameraLabels.KEY_TELE -> "Телевик"
        else -> key
    }
}
