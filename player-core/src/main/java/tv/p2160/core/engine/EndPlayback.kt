package tv.p2160.core.engine

import androidx.media3.common.Player
import tv.p2160.core.resume.ResumeStore
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

/**
 * Конец файла настоящий, только если длительность известна и позиция в допуске [ResumeStore.isFinished].
 * Обрыв медленного потока (1:20 из 24:01) сюда не входит. Конец ролика, у которого внешний звук длиннее
 * видео, входит: склейка обрезает звук длительностью видео, и позиция доходит до неё.
 */
internal fun verifiedNaturalEnd(positionMs: Long, durationMs: Long): Boolean =
    ResumeStore.isFinished(positionMs, durationMs)

/**
 * Позиция в результате. Длительность подставляется после проверенного конца
 * или по прежнему порогу «почти досмотрел». Ранний обрыв позицию не подменяет.
 */
internal data class PlaybackReport(val positionMs: Long, val durationMs: Long, val completed: Boolean)

internal fun playbackReport(positionMs: Long, durationMs: Long, verifiedEnd: Boolean): PlaybackReport {
    val duration = durationMs.coerceAtLeast(0)
    val position = positionMs.coerceAtLeast(0)
    if (verifiedEnd) return PlaybackReport(duration, duration, completed = true)
    val watched = ResumeStore.isFinished(position, duration)
    return PlaybackReport(if (watched) duration else position, duration, completed = watched)
}

/** Сколько раз повторять источник, если плеер сообщил конец далеко от длительности. Дальше - обычная ошибка. */
internal const val PREMATURE_END_RETRIES = 3

internal fun prematureEndExhausted(attempts: Int): Boolean = attempts >= PREMATURE_END_RETRIES

/**
 * Заставка при паузе копится только на паузе пользователя: `playWhenReady == false` и [Player.STATE_READY].
 * Буферизация (`playWhenReady` ещё true, состояние BUFFERING) и конец файла сюда не входят.
 */
internal fun countsAsUserPause(playWhenReady: Boolean, playbackState: Int): Boolean =
    !playWhenReady && playbackState == Player.STATE_READY

/** Пора ли затемнять кадр заставкой паузы. Буферизация, конец и выключенная настройка - нет. */
internal fun pauseSaverShouldDim(
    playWhenReady: Boolean,
    playbackState: Int,
    oledSaver: Boolean,
    heldForMs: Long,
    blocked: Boolean,
): Boolean {
    if (!oledSaver || blocked || !countsAsUserPause(playWhenReady, playbackState)) return false
    return endShouldDim(holdingEnd = true, live = false, heldForMs = heldForMs)
}

/**
 * Кнопки видны, а фокус не на них (ни на чём или на корне экрана).
 * OK в этом случае - пауза, а не «+10 с» и не следующая глава.
 */
internal fun playPauseWhenUnfocused(controlsVisible: Boolean, rootFocused: Boolean, windowHasFocus: Boolean): Boolean =
    controlsVisible && (rootFocused || !windowHasFocus)

/**
 * Сторож кадров: воспроизведение уже шло, а не «так и не началось».
 * Кадр уже был, либо часы ушли дальше загруженного куска, а кадров нет.
 */
internal fun stallIsMidPlayback(sawFrame: Boolean, positionMs: Long, bufferedPositionMs: Long): Boolean {
    if (sawFrame) return true
    if (positionMs <= 0) return false
    return bufferedPositionMs >= 0 && positionMs > bufferedPositionMs
}

/** Куда возвращаться после обрыва: последняя позиция, где ещё шли кадры. */
internal fun stallReopenPositionMs(lastGoodMs: Long, currentMs: Long): Long =
    if (lastGoodMs >= 0) lastGoodMs else currentMs.coerceAtLeast(0)

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
