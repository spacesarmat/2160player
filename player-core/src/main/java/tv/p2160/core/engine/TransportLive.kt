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

/** Остаток и полная длительность. Пока длительность неизвестна, строку прячем. */
internal fun transportShowsRemaining(isLive: Boolean, durationMs: Long): Boolean =
    !isLive && durationMs > 0
