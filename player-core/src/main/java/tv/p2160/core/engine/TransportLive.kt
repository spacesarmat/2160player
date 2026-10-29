package tv.p2160.core.engine

/**
 * «ЭФИР» только когда плеер считает текущий элемент живым,
 * либо динамическим и с явной live-конфигурацией.
 * Неизвестная длительность (ещё не открытый файл) сюда не входит.
 */
internal fun transportIsLive(
    isCurrentMediaItemLive: Boolean,
    isCurrentMediaItemDynamic: Boolean,
    hasLiveConfiguration: Boolean,
): Boolean = isCurrentMediaItemLive || (isCurrentMediaItemDynamic && hasLiveConfiguration)

/**
 * «ЭФИР» на экране. Пока файл ещё открывается, Media3 на мгновение помечает его живым
 * (длина неизвестна и seek map ещё без длительности). Плашку не показываем, пока это не
 * подтвердилось готовностью или известной длительностью. Телеканалы известны сразу.
 */
internal fun transportShowsLiveBadge(
    requestedLive: Boolean,
    playerSaysLive: Boolean,
    durationMs: Long,
    playbackReady: Boolean,
): Boolean {
    if (requestedLive) return true
    if (!playerSaysLive) return false
    return durationMs > 0 || playbackReady
}

/** Остаток и полная длительность. Пока длительность неизвестна, строку прячем. */
internal fun transportShowsRemaining(isLive: Boolean, durationMs: Long): Boolean =
    !isLive && durationMs > 0
