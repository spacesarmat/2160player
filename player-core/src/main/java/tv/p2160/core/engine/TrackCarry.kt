package tv.p2160.core.engine

/**
 * Предпочтение дорожки, которое переносится на следующую серию.
 * Внешняя дорожка ищется по id, затем по имени (студия), затем по языку.
 * Встроенная - по индексу, затем по языку.
 */
data class CarryHint(
    val externalId: String? = null,
    val name: String? = null,
    val language: String? = null,
    val embeddedIndex: Int? = null,
    val off: Boolean = false,
)

/** Дорожка, уже показанная плеером, в том виде, в каком её видит перенос выбора. */
data class CarryTrack(
    val externalId: String? = null,
    val name: String? = null,
    val language: String? = null,
    val external: Boolean = false,
    val embeddedIndex: Int? = null,
)

object TrackCarry {

    /** Индекс в [tracks] или null, если подходящей дорожки нет. `off` сюда не попадает. */
    fun pick(hint: CarryHint, tracks: List<CarryTrack>): Int? {
        if (hint.off || tracks.isEmpty()) return null
        val lang = TrackRules.normalizeLang(hint.language)
        if (!hint.externalId.isNullOrBlank()) {
            tracks.indexOfFirst { it.external && it.externalId == hint.externalId }
                .takeIf { it >= 0 }?.let { return it }
        }
        val externalByName = hint.embeddedIndex == null && !hint.name.isNullOrBlank()
        if (externalByName) {
            tracks.indexOfFirst { it.external && it.name.equals(hint.name, ignoreCase = true) }
                .takeIf { it >= 0 }?.let { return it }
        }
        if (hint.externalId.isNullOrBlank() && hint.embeddedIndex != null) {
            tracks.indexOfFirst { !it.external && it.embeddedIndex == hint.embeddedIndex }
                .takeIf { it >= 0 }?.let { return it }
        }
        if (lang != null) {
            if (!hint.externalId.isNullOrBlank() || externalByName) {
                tracks.indexOfFirst { it.external && TrackRules.normalizeLang(it.language) == lang }
                    .takeIf { it >= 0 }?.let { return it }
            }
            tracks.indexOfFirst { !it.external && TrackRules.normalizeLang(it.language) == lang }
                .takeIf { it >= 0 }?.let { return it }
            tracks.indexOfFirst { it.external && TrackRules.normalizeLang(it.language) == lang }
                .takeIf { it >= 0 }?.let { return it }
        }
        return null
    }
}
