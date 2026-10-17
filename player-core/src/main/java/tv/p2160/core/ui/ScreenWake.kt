package tv.p2160.core.ui

/**
 * Нужно ли держать экран включённым.
 * Только пока реально идёт видео или прямой эфир (включая буферизацию после нажатия «играть»).
 * Пауза, конец, ошибка и выход — нет. Музыка без картинки экран не держит.
 */
internal fun shouldKeepScreenOn(
    enabled: Boolean,
    isPlaying: Boolean,
    isBuffering: Boolean,
    playWhenReady: Boolean,
    ended: Boolean,
    hasError: Boolean,
    hasVideo: Boolean,
    isLive: Boolean,
): Boolean {
    if (!enabled || ended || hasError) return false
    val running = isPlaying || (isBuffering && playWhenReady)
    if (!running) return false
    return hasVideo || isLive
}
