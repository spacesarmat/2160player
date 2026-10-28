package tv.p2160.core.engine

import tv.p2160.core.settings.EndAction

/** Старое «автоматически следующий» без нового ключа настроек. */
internal fun endActionFromLegacy(autoPlayNext: Boolean): EndAction =
    if (autoPlayNext) EndAction.NEXT else EndAction.STAY

internal data class EndPlan(
    val countdown: Boolean = false,
    val endScreen: Boolean = false,
    val repeat: Boolean = false,
    val closeNow: Boolean = false,
    val stay: Boolean = false,
)

/**
 * План на конец файла.
 * [live] глушит всё: эфир не закрываем и не листаем.
 * Последний файл плейлиста и одиночный файл - экран конца.
 */
internal fun endPlan(action: EndAction, hasNext: Boolean, live: Boolean): EndPlan {
    if (live) return EndPlan(stay = true)
    return when (action) {
        EndAction.REPEAT -> EndPlan(repeat = true)
        EndAction.CLOSE -> EndPlan(closeNow = true)
        EndAction.STAY -> EndPlan(stay = true)
        EndAction.NEXT -> if (hasNext) EndPlan(countdown = true) else EndPlan(endScreen = true)
    }
}

/** «Остаться на кадре» и отменённый отсчёт нельзя держать на OLED бесконечно. */
internal fun endShouldDim(holdingEnd: Boolean, live: Boolean, heldForMs: Long): Boolean =
    holdingEnd && !live && heldForMs >= END_DIM_MS

internal const val END_COUNTDOWN_SEC = 5
internal const val END_IDLE_CLOSE_MS = 12_000L
internal const val END_DIM_MS = 3 * 60_000L
