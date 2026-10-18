package tv.p2160.core.ui

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Числа в интерфейсе: один формат на плеер и приложение, десятичный разделитель — из локали
 * (в русской — запятая). Группы разрядов не ставим: скорости и зум короткие.
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

/** Ширина боковой панели плеера, dp: 42 % экрана, но не уже 300 и не шире 420. */
fun sidePanelWidthDp(availableDp: Float): Float {
    if (availableDp <= 0f) return availableDp
    return (availableDp * 0.42f).coerceIn(300f, 420f).coerceAtMost(availableDp)
}
