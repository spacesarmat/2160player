package tv.p2160.core.ui

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Числа в интерфейсе: один формат на плеер и приложение, десятичный разделитель - из локали
 * (в русской - запятая). Группы разрядов не ставим: скорости и зум короткие.
 */
object UiFormat {
    fun decimal(value: Float, fractionDigits: Int, locale: Locale = Locale.getDefault()): String {
        val fmt = NumberFormat.getNumberInstance(locale)
        fmt.isGroupingUsed = false
        fmt.minimumFractionDigits = fractionDigits
        fmt.maximumFractionDigits = fractionDigits
        fmt.roundingMode = RoundingMode.HALF_UP
        return fmt.format(value.toDouble())
    }

    /** Скорость в списках: `1`, `1,5`, `0,25`. */
    fun speed(value: Float, locale: Locale = Locale.getDefault()): String {
        val rounded = round(value * 100f) / 100f
        val asInt = rounded.toInt()
        val digits = when {
            abs(rounded - asInt) < 0.001f -> 0
            abs(rounded * 10f - round(rounded * 10f)) < 0.001f -> 1
            else -> 2
        }
        return decimal(rounded, digits, locale)
    }

    /** Точная скорость в шаговом регуляторе: всегда два знака (`1,00`). */
    fun speedFine(value: Float, locale: Locale = Locale.getDefault()): String = decimal(value, 2, locale)

    /** Зум и усиление микрофона: один знак (`1,0`). */
    fun zoom(value: Float, locale: Locale = Locale.getDefault()): String = decimal(value, 1, locale)
}

/**
 * Ширина боковой панели, dp.
 * Узкий портрет (короче 600 dp, как 360 dp у S21 Ultra при высокой плотности) - почти на весь экран,
 * не уже 320 dp, если ширина позволяет. Альбом и широкий экран - доля ширины, от 320 до 520 dp.
 */
fun sidePanelWidthDp(availableDp: Float): Float {
    if (availableDp <= 0f) return availableDp
    if (availableDp < 600f) {
        return (availableDp * 0.92f).coerceAtLeast(320f).coerceAtMost(availableDp)
    }
    return (availableDp * 0.46f).coerceIn(320f, 520f).coerceAtMost(availableDp)
}

/**
 * Отступ сверху боковой панели, dp: панель начинается под верхней полосой кнопок,
 * иначе на широком телефоне она накрывает иконки меню. 8+48+8; в узком портрете
 * кнопки во втором ряду - ещё 48. Вызывающий добавляет сверху safeDrawing обычным
 * padding, не windowInsetsPadding: вместе со сдвигом в measure это заново измеряло
 * кадр на каждом кадре анимации (0.2.10).
 */
fun sidePanelTopClearanceDp(narrow: Boolean): Float = if (narrow) 112f else 64f

/** Зазор между временем у полосы перемотки и краем открытой панели, dp. */
const val sidePanelTransportGapDp = 12f
