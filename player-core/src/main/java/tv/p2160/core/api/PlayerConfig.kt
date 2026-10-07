package tv.p2160.core.api

/**
 * Тонкая настройка движка для встраивающих приложений — то, что пользователь в настройках не меняет.
 * Задаётся глобально через [Player2160.config] (до создания плеера) или передаётся в конструктор
 * [tv.p2160.core.engine.PlayerController]. Действует на плееры, созданные после изменения.
 */
data class PlayerConfig(
    /**
     * Предел буфера в байтах. 4K-ремукс с большим битрейтом без предела съедает ~130 МБ —
     * на слабых ТВ и приставках это нехватка памяти. [AUTO] (по умолчанию) — по памяти устройства
     * ([DeviceProfile.bufferTargetBytes]: 24–128 МБ), [UNLIMITED] — правило Media3.
     */
    val bufferTargetBytes: Int = AUTO,
    /** Сколько держать в буфере, мс: минимум и максимум (пока позволяет [bufferTargetBytes]). */
    val minBufferMs: Int = 50_000,
    val maxBufferMs: Int = 50_000,
    /** Сколько набрать перед стартом и после «подгрузки», мс. */
    val bufferForPlaybackMs: Int = 1_000,
    val bufferForPlaybackAfterRebufferMs: Int = 2_000,
    /** Тайм-ауты HTTP(S): соединение и чтение, мс. У торрентов свой тайм-аут ожидания частей (TorrentPrefs). */
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 60_000,
    /**
     * Поиск вступления и титров по звуку (сравнение с соседними сериями). Читает начало соседних
     * файлов по сети — выключите, если отрезки даёт ваш медиасервер.
     */
    val introDetection: Boolean = true,
    /** Читать главы файла через FFmpeg при старте (отдельное открытие файла). Главы Blu-ray-дисков читаются всегда. */
    val readChapters: Boolean = true,
    /**
     * Брать из истории 2160 Player позицию, дорожки, скорость и задержку субтитров, а для новых файлов —
     * выбор дорожек «по привычке» ([tv.p2160.core.settings.Settings.smartTracks]). Выключите, если
     * продолжение просмотра и выбор дорожек ведёт ваше приложение: плеер не будет подменять дорожки сам
     * (явная [PlaybackRequest.startPositionMs] и `select` у внешних субтитров работают в любом случае).
     */
    val restoreFromHistory: Boolean = true,
    /** Сохранять прогресс в историю 2160 Player ([tv.p2160.core.resume.ResumeStore]). */
    val saveHistory: Boolean = true,
) {
    companion object {
        /** Без предела буфера в байтах — правило Media3. */
        const val UNLIMITED = -1
        /** Предел буфера по памяти устройства ([DeviceProfile.bufferTargetBytes]). */
        const val AUTO = -2
    }
}
