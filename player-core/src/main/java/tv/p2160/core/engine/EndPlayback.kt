package tv.p2160.core.engine

import tv.p2160.core.settings.EndAction

/** Старое «автоматически следующий» без нового ключа настроек. */
internal fun endActionFromLegacy(autoPlayNext: Boolean): EndAction =
    if (autoPlayNext) EndAction.NEXT else EndAction.STAY

internal data class EndPlan(
    val countdown: Boolean = false,
    val endScreen: Boolean = false,
    val playlistDone: Boolean = false,
    val repeat: Boolean = false,
    val closeNow: Boolean = false,
    val stay: Boolean = false,
)

/**
 * План на конец файла.
 * [live] глушит всё: эфир не закрываем и не листаем.
 * Один файл - экран конца. Последний в плейлисте из нескольких - короткая надпись и возврат в папку.
 */
internal fun endPlan(action: EndAction, hasNext: Boolean, playlistSize: Int, live: Boolean): EndPlan {
    if (live) return EndPlan(stay = true)
    return when (action) {
        EndAction.REPEAT -> EndPlan(repeat = true)
        EndAction.CLOSE -> EndPlan(closeNow = true)
        EndAction.STAY -> EndPlan(stay = true)
        EndAction.NEXT -> when {
            hasNext -> EndPlan(countdown = true)
            playlistSize > 1 -> EndPlan(playlistDone = true)
            else -> EndPlan(endScreen = true)
        }
    }
}

/** Карточка «Дальше» за 15 с до конца или на последней главе, похожей на титры. Фокус сама не берёт. */
internal fun showUpNext(
    action: EndAction,
    hasNext: Boolean,
    live: Boolean,
    ended: Boolean,
    positionMs: Long,
    durationMs: Long,
    inLastChapter: Boolean,
    lastChapterTitle: String?,
): Boolean {
    if (live || ended || !hasNext || action != EndAction.NEXT) return false
    if (inLastChapter && looksLikeCredits(lastChapterTitle)) return true
    if (durationMs <= 0 || positionMs <= 0) return false
    val remaining = durationMs - positionMs
    return remaining in 1..UP_NEXT_WINDOW_MS
}

internal fun looksLikeCredits(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    val t = title.lowercase()
    return "credit" in t || "титр" in t || "ending" in t || "конец" in t
}

/** Частично просмотренный файл: предложить продолжить. Явный старт и выключенное автопродолжение не трогаем. */
internal fun resumeOfferMs(
    autoResume: Boolean,
    explicitStart: Long?,
    savedMs: Long?,
    finished: Boolean,
    live: Boolean,
): Long? {
    if (!autoResume || explicitStart != null || live || finished) return null
    val saved = savedMs ?: return null
    if (saved <= 5_000L) return null
    return saved
}

/** «Остаться на кадре» и отменённый отсчёт нельзя держать на OLED бесконечно. */
internal fun endShouldDim(holdingEnd: Boolean, live: Boolean, heldForMs: Long): Boolean =
    holdingEnd && !live && heldForMs >= END_DIM_MS

internal const val END_COUNTDOWN_SEC = 5
internal const val END_IDLE_CLOSE_MS = 12_000L
internal const val END_DIM_MS = 3 * 60_000L
internal const val PLAYLIST_DONE_MS = 2_500L
internal const val UP_NEXT_WINDOW_MS = 15_000L

/** Широкий экран: карточка «Далее» стоит справа, подсказка продолжения остаётся слева от неё. */
internal fun upNextCardReservesSide(widthDp: Int): Boolean = widthDp >= 600

/**
 * Узкое низкое окно: карточка снизу достаёт до подсказки сверху.
 * Пока подсказка видна, карточку не показываем.
 */
internal fun deferUpNextWhileResumeToast(
    resumeToast: Boolean,
    widthDp: Int,
    heightDp: Int,
    controlsVisible: Boolean,
): Boolean {
    if (!resumeToast || upNextCardReservesSide(widthDp)) return false
    val cardBottom = if (controlsVisible) 110 else 32
    val cardTop = heightDp - cardBottom - 240
    return cardTop < 72 + 64
}
