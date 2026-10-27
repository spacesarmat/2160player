package tv.p2160.core.intro

/**
 * Поиск вступления не стартует, пока плеер не дошёл до [androidx.media3.common.Player.STATE_READY],
 * и не крутится по мёртвому источнику: несколько попыток с паузой, дальше стоп.
 */
internal object IntroStart {
    const val MAX_ATTEMPTS = 3

    fun allowed(ready: Boolean, hasError: Boolean, attempts: Int): Boolean =
        ready && !hasError && attempts < MAX_ATTEMPTS

    /** Пауза перед попыткой с этим номером (0 - первая, уже после готовности плеера). */
    fun delayMs(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
        0 -> 5_000L
        1 -> 20_000L
        else -> 60_000L
    }

    /** Повтор только если звук так и не открылся. Пустой результат после чтения - не повтор. */
    fun retryAfter(sourceOpened: Boolean, attempts: Int): Boolean =
        !sourceOpened && attempts < MAX_ATTEMPTS

    /**
     * HTTP без ответа не отдаём в MediaExtractor: тот сам повторяет MediaHTTPConnection много раз.
     * [hostAnswered] null - схема не сетевая, проба не нужна.
     */
    fun openExtractor(scheme: String?, hostAnswered: Boolean?): Boolean {
        val http = scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        return !http || hostAnswered == true
    }
}
