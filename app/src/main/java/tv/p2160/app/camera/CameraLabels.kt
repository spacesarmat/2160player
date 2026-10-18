package tv.p2160.app.camera

import tv.p2160.core.ui.UiFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * Имена камер в ряду «Камера». Одна камера стороны — «Задняя» / «Фронтальная».
 * Несколько задних: ближайшая к 26 мм экв. — «Основная», шире — «Широкая» или «Сверхширокая»,
 * длиннее — «Телевик 3×». Вторая фронтальная называется по отличию фокусного
 * («Фронтальная широкая»), номер — только если отличить нельзя.
 * Логическая камера, чьи физические id уже есть в списке, в ряд не попадает.
 */
object CameraLabels {
    /** Совпадают с [android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT] и соседними. */
    const val FRONT = 0
    const val BACK = 1
    const val EXTERNAL = 2

    const val KEY_BACK = "camera.back_main"
    const val KEY_FRONT = "camera.front"
    const val KEY_EXTERNAL = "camera.external"
    const val KEY_MAIN = "camera.main"
    const val KEY_WIDE = "camera.wide"
    const val KEY_ULTRA = "camera.ultra"
    const val KEY_TELE = "camera.back_tele"

    data class Lens(
        val id: String,
        val facing: Int,
        val focalLengthMm: Float,
        val sensorWidthMm: Float? = null,
        /** Логическая мульти-камера: физические объективы уже перечислены отдельно. */
        val logicalDuplicate: Boolean = false,
    )

    data class Name(
        val key: String,
        /** Множитель относительно основной камеры этой стороны. null — в подпись не входит. */
        val zoom: Float? = null,
        /** Сторона, если без неё имя совпадает с камерой другой стороны или это не основная фронтальная. */
        val facingKey: String? = null,
        /** 2 и дальше, если имя всё ещё повторяется. Первая остаётся без номера. */
        val number: Int? = null,
    ) {
        fun text(translate: (String) -> String, locale: Locale = Locale.getDefault()): String {
            val zoomText = zoom?.let { UiFormat.speed(displayZoom(it), locale) + "×" }
            val parts = ArrayList<String>(3)
            if (facingKey != null) parts += translate(facingKey)
            val role = translate(key)
            // «Фронтальная широкая», не «Фронтальная Широкая»: второе слово — уточнение.
            parts += if (facingKey != null && key != facingKey) decapitalize(role, locale) else role
            if (zoomText != null) parts += zoomText
            val body = parts.joinToString(" ")
            return if (number != null && number > 1) "$body $number" else body
        }
    }

    /** Целый множитель, если он близок («2,9» → «3»); иначе один знак. */
    fun displayZoom(zoom: Float): Float {
        if (!zoom.isFinite() || zoom <= 0f) return 1f
        val nearest = round(zoom)
        if (nearest >= 1f && abs(zoom - nearest) <= 0.15f) return nearest
        return round(zoom * 10f) / 10f
    }

    /**
     * Одно фокусное для подписи. У логической камеры в массиве несколько объективов:
     * берём ближайший к 26 мм экв. (обычная широкая), а не самый короткий — иначе основная
     * камера называлась бы сверхширокой.
     */
    fun pickFocal(focals: FloatArray?, sensorWidthMm: Float?): Float {
        if (focals == null || focals.isEmpty()) return 0f
        if (focals.size == 1) return focals[0]
        if (sensorWidthMm != null && sensorWidthMm > 0f) {
            return focals.minBy { abs(it * 36f / sensorWidthMm - 26f) }
        }
        val sorted = focals.sorted()
        return sorted[sorted.size / 2]
    }

    fun names(lenses: List<Lens>): List<Name> {
        val visible = lenses.filter { !it.logicalDuplicate }
        if (visible.isEmpty()) return emptyList()
        val preliminary = HashMap<String, Name>(visible.size)
        val facingOf = HashMap<String, Int>(visible.size)
        for ((facing, group) in visible.groupBy { it.facing }) {
            labelGroup(facing, group).forEach { (lens, name) ->
                preliminary[lens.id] = name
                facingOf[lens.id] = facing
            }
        }
        val ids = visible.map { it.id }
        val signatures = ids.map { sig(preliminary.getValue(it)) }
        val facingsBySig = HashMap<String, MutableSet<Int>>()
        val countBySig = HashMap<String, Int>()
        ids.forEachIndexed { i, id ->
            val s = signatures[i]
            countBySig[s] = (countBySig[s] ?: 0) + 1
            facingsBySig.getOrPut(s) { HashSet() }.add(facingOf.getValue(id))
        }
        val seen = HashMap<Pair<String, String?>, Int>()
        val bucketSize = HashMap<Pair<String, String?>, Int>()
        val prefixes = ids.map { id ->
            val name = preliminary.getValue(id)
            val s = sig(name)
            val facing = facingOf.getValue(id)
            val collide = (countBySig[s] ?: 1) > 1
            val mixed = (facingsBySig[s]?.size ?: 1) > 1
            val prefix = if (collide && mixed && name.key != facingKey(facing) && name.facingKey == null) {
                facingKey(facing)
            } else {
                null
            }
            val bucket = s to (prefix ?: name.facingKey)
            bucketSize[bucket] = (bucketSize[bucket] ?: 0) + 1
            bucket to prefix
        }
        return ids.mapIndexed { i, id ->
            val (bucket, prefix) = prefixes[i]
            val n = (seen[bucket] ?: 0) + 1
            seen[bucket] = n
            val total = bucketSize[bucket] ?: 1
            val existing = preliminary.getValue(id)
            existing.copy(
                facingKey = prefix ?: existing.facingKey,
                number = if (total > 1 && n > 1) n else null,
            )
        }
    }

    private fun labelGroup(facing: Int, group: List<Lens>): List<Pair<Lens, Name>> {
        if (group.size == 1) return listOf(group[0] to Name(facingKey(facing)))
        val useEquiv = group.all { (it.sensorWidthMm ?: 0f) > 0f && it.focalLengthMm > 0f }
        fun metric(lens: Lens): Float = when {
            useEquiv -> lens.focalLengthMm * 36f / lens.sensorWidthMm!!
            else -> lens.focalLengthMm.coerceAtLeast(0f)
        }
        val reference = if (useEquiv) group.minBy { abs(metric(it) - 26f) } else group.first()
        val refMetric = metric(reference).takeIf { it > 0f }
        val zooms = group.map { lens ->
            val m = metric(lens)
            if (refMetric == null || m <= 0f) 1f else m / refMetric
        }
        val ultraCount = zooms.count { it < 0.8f }
        return group.zip(zooms).map { (lens, zoom) ->
            val isRef = lens.id == reference.id
            val key = when {
                isRef -> if (facing == BACK) KEY_MAIN else facingKey(facing)
                zoom < 0.8f -> KEY_ULTRA
                zoom < 0.98f -> KEY_WIDE
                zoom <= 1.02f -> if (facing == BACK) KEY_MAIN else facingKey(facing)
                else -> KEY_TELE
            }
            val showZoom = when (key) {
                KEY_TELE -> zoom.isFinite() && zoom > 0f && displayZoom(zoom) != 1f
                KEY_ULTRA -> ultraCount > 1 && zoom.isFinite() && zoom > 0f
                else -> false
            }
            // Не основная фронтальная (и внешняя) всегда говорит, чем отличается, а не только «Широкая».
            val prefix = if (!isRef && facing != BACK && key != facingKey(facing)) facingKey(facing) else null
            lens to Name(
                key = key,
                zoom = if (showZoom) zoom else null,
                facingKey = prefix,
            )
        }
    }

    private fun facingKey(facing: Int): String = when (facing) {
        FRONT -> KEY_FRONT
        EXTERNAL -> KEY_EXTERNAL
        else -> KEY_BACK
    }

    private fun sig(name: Name): String {
        val zoom = name.zoom?.let { displayZoom(it) }
        return "${name.facingKey.orEmpty()}|${name.key}@$zoom"
    }
}

private fun decapitalize(text: String, locale: Locale): String =
    if (text.isEmpty()) text else text.substring(0, 1).lowercase(locale) + text.substring(1)
