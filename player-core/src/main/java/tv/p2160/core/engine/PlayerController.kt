package tv.p2160.core.engine

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import tv.p2160.core.api.Chapter
import tv.p2160.core.bluray.DiscSession
import tv.p2160.core.bluray.DiscSessions
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.api.NowPlaying
import tv.p2160.core.api.PlayerExtensions
import tv.p2160.core.api.SegmentType
import tv.p2160.core.api.SkipSegment
import tv.p2160.core.settings.SkipMode
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.PlaybackResult
import tv.p2160.core.i18n.I18n
import tv.p2160.core.resume.ResumeEntry
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.settings.PlayerSettings
import tv.p2160.core.subtitle.SubtitleSupport

data class PlayerUiState(
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
    val videoTracks: List<TrackOption> = emptyList(),
    val textDisabled: Boolean = false,
    val subtitleDelayMs: Long = 0,
    val hasVideo: Boolean = true,
    val videoAspect: Float = 0f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val playlistIndex: Int = 0,
    val playlistSize: Int = 1,
    /** Позиция, с которой продолжили просмотр (для подсказки «Продолжено с …»). */
    val resumedFromMs: Long? = null,
    val error: String? = null,
    val chapters: List<Chapter> = emptyList(),
    /** Индекс текущей главы в [chapters] или -1. */
    val chapterIndex: Int = -1,
    val segments: List<SkipSegment> = emptyList(),
    /** Отрезок, внутри которого сейчас позиция (для кнопки «Пропустить»). */
    val activeSegment: SkipSegment? = null,
    /** Название сериала, если ручные отметки применяются ко всем сериям; null — только к этому файлу. */
    val seriesName: String? = null,
    val marks: ManualMarks = ManualMarks(),
    /** Временное ускорение (удержание пальца) включено. */
    val speedBoost: Boolean = false,
    /** Format.id вторых субтитров или null. */
    val secondaryTextId: String? = null,
)

/**
 * Логика воспроизведения, не зависящая от UI: собирает ExoPlayer, восстанавливает
 * позицию/дорожки/скорость, периодически сохраняет прогресс, управляет субтитрами.
 * Все методы вызываются с главного потока.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val request: PlaybackRequest,
) {
    private val appContext = context.applicationContext
    private val settings = PlayerSettings.get(appContext)
    private val store = ResumeStore.get(appContext)
    private val i18n = I18n.get(appContext)
    private val segmentStore = SegmentStore.get(appContext)
    private val analyzer = MediaAnalyzer(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val built = PlayerFactory.build(appContext, settings.current, request.headers)
    val player: ExoPlayer get() = built.player
    /** Реплики вторых субтитров — рисуются отдельным слоем сверху. */
    val secondaryCues get() = built.secondarySubtitles.cues

    private val _state = MutableStateFlow(PlayerUiState(playlistSize = request.items.size))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Внешние субтитры по индексу плейлиста (растут при ручном добавлении). */
    private val subtitles: MutableList<MutableList<ExternalSubtitle>> =
        request.items.map { it.subtitles.toMutableList() }.toMutableList()

    /** Сохранённое состояние, которое нужно применить, когда станут известны дорожки. */
    private var pendingRestore: ResumeEntry? = null
    /** Внешний субтитр, который нужно выбрать после пересборки MediaItem. */
    private var pendingExternalSelect: String? = null
    private var completed = false
    private var progressJob: Job? = null

    // Главы и отрезки текущего элемента.
    private var chapters: List<Chapter> = emptyList()
    private var baseSegments: List<SkipSegment> = emptyList()
    private var marksKey: String? = null
    private var marks = ManualMarks()
    /** Отрезки, уже пропущенные автоматически: если пользователь вернулся назад, второй раз не прыгаем. */
    private val autoSkipped = mutableSetOf<SkipSegment>()
    private var analyzeJob: Job? = null
    /** Открытые диски Blu-ray по индексу плейлиста. */
    private val discs = HashMap<Int, DiscSession>()
    private var speedBeforeBoost: Float? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onTracksChanged(tracks: Tracks) {
            applyPendingSelections(tracks)
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            setSecondarySubtitle(null)
            onItemStarted(player.currentMediaItemIndex, explicitStart = null)
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Переход к следующему элементу: сохраняем прогресс предыдущего как досмотренный.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && old.mediaItemIndex != new.mediaItemIndex) {
                val window = player.currentTimeline.takeIf { old.mediaItemIndex < it.windowCount }
                    ?.getWindow(old.mediaItemIndex, androidx.media3.common.Timeline.Window())
                val duration = window?.durationMs?.takeIf { it != C.TIME_UNSET } ?: return
                saveFor(old.mediaItemIndex, duration, duration)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                completed = true
                saveProgress()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            _state.update { it.copy(error = describe(error)) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = refresh()
    }

    init {
        player.addListener(listener)
        player.pauseAtEndOfMediaItems = !settings.current.autoPlayNext
        scope.launch { start() }
    }

    private suspend fun start() {
        val mediaItems = request.items.indices.map { buildMediaItem(it) }
        val startIndex = request.startIndex.coerceIn(mediaItems.indices)
        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        onItemStarted(startIndex, explicitStart = request.startPositionMs)
        player.prepare()
        player.playWhenReady = true
        startProgressLoop()
    }

    /** Восстанавливает позицию, скорость и дорожки для текущего элемента плейлиста. */
    private fun onItemStarted(index: Int, explicitStart: Long?) {
        completed = false
        val entry = keyAt(index)?.let(store::get)
        val s = settings.current

        val resumeFrom = when {
            explicitStart != null -> explicitStart.takeIf { it > 0 }
            !s.autoResume || entry == null || entry.finished -> null
            entry.positionMs > 5_000 -> entry.positionMs
            else -> null
        }
        resumeFrom?.let { player.seekTo(index, it) }

        val speed = entry?.speed?.takeIf { it != 1f } ?: s.defaultSpeed
        player.playbackParameters = PlaybackParameters(speed)
        player.subtitleDelayMilliseconds = entry?.subtitleDelayMs ?: 0L

        pendingRestore = entry
        // Явно запрошенные внешние субтитры важнее сохранённого выбора.
        subtitles.getOrNull(index)?.firstOrNull { it.select }?.let { pendingExternalSelect = externalId(index, it) }

        _state.update { it.copy(resumedFromMs = resumeFrom, error = null, title = titleAt(index)) }
        applyPendingSelections(player.currentTracks)
        loadChaptersAndSegments(index)
    }

    private fun loadChaptersAndSegments(index: Int) {
        val entry = request.items.getOrNull(index) ?: return
        chapters = emptyList()
        autoSkipped.clear()
        val title = titleAt(index)
        val fileName = SubtitleSupport.displayName(appContext, entry.uri) ?: title
        val series = SegmentDetector.seriesKey(fileName) ?: SegmentDetector.seriesKey(title)
        marksKey = series?.let { "series:$it" } ?: keyAt(index)?.let { "file:$it" }
        marks = marksKey?.let(segmentStore::get) ?: ManualMarks()
        baseSegments = entry.segments
        _state.update { it.copy(seriesName = series, marks = marks) }

        analyzeJob?.cancel()
        analyzeJob = scope.launch {
            val found = discs[index]?.let { d ->
                // Главы диска — из плейлиста (FFmpeg в ISO не заглянет).
                val starts = d.title.chapters
                starts.mapIndexed { i, start -> Chapter(null, start, starts.getOrElse(i + 1) { d.title.durationMs }) }
            } ?: runCatching { analyzer.chapters(entry.uri) }.getOrDefault(emptyList())
            if (player.currentMediaItemIndex != index) return@launch
            chapters = found
            // Явно переданные отрезки (Intent/медиасервер) важнее найденных по главам.
            val explicitTypes = entry.segments.map { it.type }.toSet()
            baseSegments = entry.segments + SegmentDetector.fromChapters(found).filter { it.type !in explicitTypes }
            refresh()
        }
    }

    private fun currentSegments(durationMs: Long): List<SkipSegment> {
        val manual = marks.toSegments(durationMs)
        val manualTypes = manual.map { it.type }.toSet()
        return (baseSegments.filter { it.type !in manualTypes } + manual).sortedBy { it.startMs }
    }

    private fun applyPendingSelections(tracks: Tracks) {
        if (tracks.isEmpty) return
        pendingExternalSelect?.let { id ->
            val option = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current).firstOrNull {
                tracks.groups[it.groupIndex].getTrackFormat(it.trackIndex).id?.endsWith(id) == true
            }
            if (option != null) {
                select(option)
                pendingExternalSelect = null
            }
        }
        val entry = pendingRestore ?: return
        pendingRestore = null

        val audio = TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current)
        (audio.firstOrNull { entry.audioLabel != null && it.label == entry.audioLabel }
            ?: audio.firstOrNull { entry.audioLanguage != null && it.language == entry.audioLanguage })
            ?.takeUnless { it.selected }?.let(::select)

        if (pendingExternalSelect == null) {
            if (entry.textDisabled) {
                disableSubtitles()
            } else {
                val text = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current)
                (text.firstOrNull { entry.textLabel != null && it.label == entry.textLabel }
                    ?: text.firstOrNull { entry.textLanguage != null && it.language == entry.textLanguage })
                    ?.takeUnless { it.selected }?.let(::select)
            }
        }
    }

    // region Управление

    fun playPause() {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { player.prepare(); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
        if (!player.playWhenReady) saveProgress()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0, duration))
        refresh()
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun next() { if (player.hasNextMediaItem()) { saveProgress(); player.seekToNextMediaItem() } }

    fun previous() {
        if (player.currentPosition > 5_000 || !player.hasPreviousMediaItem()) player.seekTo(0)
        else { saveProgress(); player.seekToPreviousMediaItem() }
    }

    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed.coerceIn(0.25f, 4f))
        refresh()
    }

    fun select(option: TrackOption) {
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
    }

    /** Сбрасывает ручной выбор видеодорожки — плеер снова выбирает качество сам (важно для HLS/DASH). */
    fun autoVideo() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /**
     * Вторые субтитры. Выбор делается «старым» per-renderer override второго рендерера:
     * обычный override по типу дорожки отключил бы все текстовые рендереры, кроме одного.
     */
    fun setSecondarySubtitle(option: TrackOption?) {
        val secondary = built.secondarySubtitles
        val selector = player.trackSelector as? DefaultTrackSelector ?: return
        val rendererIndex = (0 until player.rendererCount).firstOrNull { player.getRenderer(it) === secondary.renderer } ?: return
        val group = option?.let { player.currentTracks.groups.getOrNull(it.groupIndex) }
        secondary.formatId = option?.formatId
        val builder = selector.buildUponParameters().clearSelectionOverrides(rendererIndex)
        if (option != null && group != null) {
            builder.setRendererDisabled(rendererIndex, false)
                .setSelectionOverride(
                    rendererIndex,
                    TrackGroupArray(group.mediaTrackGroup),
                    DefaultTrackSelector.SelectionOverride(0, option.trackIndex),
                )
        } else {
            builder.setRendererDisabled(rendererIndex, true)
            secondary.clear()
        }
        selector.setParameters(builder)
        _state.update { it.copy(secondaryTextId = secondary.formatId) }
    }

    fun setSubtitleDelay(ms: Long) {
        player.subtitleDelayMilliseconds = ms
        _state.update { it.copy(subtitleDelayMs = ms) }
    }

    /** Подключает внешний файл субтитров к текущему видео и сразу включает его. */
    fun addExternalSubtitle(uri: Uri, name: String? = null) {
        val index = player.currentMediaItemIndex
        val displayName = name ?: SubtitleSupport.displayName(appContext, uri)
        val sub = ExternalSubtitle(uri, displayName, SubtitleSupport.languageFromName(displayName), select = true)
        subtitles[index].add(sub)
        scope.launch {
            val position = player.currentPosition
            val playing = player.playWhenReady
            pendingExternalSelect = externalId(index, sub)
            player.replaceMediaItem(index, buildMediaItem(index))
            player.seekTo(index, position)
            player.playWhenReady = playing
        }
    }

    // region Главы и отрезки

    fun nextChapter() {
        val pos = player.currentPosition
        chapters.firstOrNull { it.startMs > pos + 1_000 }?.let { seekTo(it.startMs) }
    }

    /** Как на CD-плеере: в начале главы — к предыдущей, иначе — к началу текущей. */
    fun previousChapter() {
        val pos = player.currentPosition
        val current = chapters.indexOfLast { it.startMs <= pos }
        if (current < 0) return
        val target = if (pos - chapters[current].startMs > 3_000 || current == 0) chapters[current] else chapters[current - 1]
        seekTo(target.startMs)
    }

    fun seekToChapter(chapter: Chapter) = seekTo(chapter.startMs)

    fun skip(segment: SkipSegment) {
        autoSkipped += segment
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        if (segment.type == SegmentType.CREDITS && (segment.endMs == null || segment.endMs >= duration - 1_000) && player.hasNextMediaItem()) {
            next()
        } else {
            seekTo(segment.endMs ?: duration)
        }
    }

    fun markIntroStart() = updateMarks { it.copy(introStartMs = player.currentPosition) }

    fun markIntroEnd() = updateMarks {
        val pos = player.currentPosition
        it.copy(introEndMs = pos, introStartMs = it.introStartMs?.takeIf { s -> s < pos } ?: 0L)
    }

    fun markCreditsStart() = updateMarks {
        val duration = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: return@updateMarks it
        it.copy(creditsFromEndMs = (duration - player.currentPosition).coerceAtLeast(0))
    }

    fun clearMarks() = updateMarks { ManualMarks() }

    private fun updateMarks(transform: (ManualMarks) -> ManualMarks) {
        val key = marksKey ?: return
        marks = transform(marks)
        segmentStore.put(key, marks)
        _state.update { it.copy(marks = marks) }
        refresh()
    }

    /** Кадр для превью при перемотке; null — источник не позволяет. */
    suspend fun frameAt(positionMs: Long): Bitmap? {
        val uri = request.items.getOrNull(player.currentMediaItemIndex)?.uri ?: return null
        return runCatching { analyzer.frameAt(uri, positionMs) }.getOrNull()
    }

    // endregion

    /** Временное ускорение, пока палец удерживается на экране. */
    fun setSpeedBoost(enabled: Boolean) {
        if (enabled && speedBeforeBoost == null) {
            speedBeforeBoost = player.playbackParameters.speed
            player.playbackParameters = PlaybackParameters(2f)
        } else if (!enabled) {
            speedBeforeBoost?.let { player.playbackParameters = PlaybackParameters(it) }
            speedBeforeBoost = null
        }
        _state.update { it.copy(speedBoost = speedBeforeBoost != null) }
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        player.prepare()
        player.play()
    }

    fun dismissResumeHint() = _state.update { it.copy(resumedFromMs = null) }

    /** Начать текущий файл с начала (кнопка в подсказке «Продолжено с …»). */
    fun restartFromBeginning() {
        player.seekTo(0)
        dismissResumeHint()
    }

    // endregion

    fun result(): PlaybackResult = PlaybackResult(
        uri = request.items.getOrNull(player.currentMediaItemIndex)?.uri,
        positionMs = player.currentPosition,
        durationMs = player.duration.coerceAtLeast(0),
        completed = completed,
    )

    fun saveProgress() {
        val duration = player.duration
        // Длительность неизвестна (поток ещё не открылся) — иначе в истории окажется «0:00».
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (completed) duration else player.currentPosition
        saveFor(player.currentMediaItemIndex, position, duration)
    }

    private fun saveFor(index: Int, positionMs: Long, durationMs: Long) {
        if (player.isCurrentMediaItemLive) return
        val key = keyAt(index) ?: return
        val entry = request.items.getOrNull(index) ?: return
        val tracks = player.currentTracks
        val audio = TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current).firstOrNull { it.selected }
        val text = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current).firstOrNull { it.selected }
        val duration = durationMs.coerceAtLeast(0)
        val finished = ResumeStore.isFinished(positionMs, duration)
        store.save(
            ResumeEntry(
                key = key,
                uri = entry.uri.toString(),
                title = titleAt(index),
                positionMs = if (finished) 0 else positionMs.coerceAtLeast(0),
                durationMs = duration,
                finished = finished,
                audioLanguage = audio?.language,
                audioLabel = audio?.label,
                textLanguage = text?.language,
                textLabel = text?.label?.takeUnless { text.external },
                textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                speed = player.playbackParameters.speed,
                subtitleDelayMs = player.subtitleDelayMilliseconds,
            )
        )
    }

    fun release() {
        saveProgress()
        publishNowPlaying()
        PlayerExtensions.publish(PlayerExtensions.nowPlaying.value?.copy(isPlaying = false))
        progressJob?.cancel()
        analyzeJob?.cancel()
        scope.cancel()
        analyzer.release()
        discs.values.forEach(DiscSessions::close)
        discs.clear()
        player.removeListener(listener)
        built.release()
    }

    private fun startProgressLoop() {
        progressJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                refresh()
                maybeAutoSkip()
                if (++ticks % 10 == 0 && player.isPlaying) saveProgress()
                if (ticks % 2 == 0) publishNowPlaying()
            }
        }
    }

    private fun publishNowPlaying() {
        val entry = request.items.getOrNull(player.currentMediaItemIndex) ?: return
        val s = _state.value
        PlayerExtensions.publish(
            NowPlaying(
                uri = entry.uri,
                title = s.title,
                positionMs = s.positionMs,
                durationMs = s.durationMs,
                isPlaying = s.isPlaying,
                headers = request.headers,
            )
        )
    }

    private fun maybeAutoSkip() {
        if (settings.current.skipMode != SkipMode.AUTO || !player.isPlaying) return
        val segment = _state.value.activeSegment ?: return
        // Титры с переходом к следующей серии обрабатывает UI (обратный отсчёт).
        if (segment.type == SegmentType.CREDITS || segment in autoSkipped) return
        skip(segment)
    }

    private fun refresh() {
        val p = player
        val tracks = p.currentTracks
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val video = p.videoSize
        val position = p.currentPosition.coerceAtLeast(0)
        val segments = if (duration > 0) currentSegments(duration) else emptyList()
        val skipMode = settings.current.skipMode
        _state.update {
            it.copy(
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                bufferedMs = p.bufferedPosition,
                speed = p.playbackParameters.speed,
                audioTracks = TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current),
                textTracks = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current),
                videoTracks = TrackLabels.collect(tracks, C.TRACK_TYPE_VIDEO, i18n.current),
                textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                subtitleDelayMs = p.subtitleDelayMilliseconds,
                hasVideo = tracks.isEmpty || tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true),
                videoAspect = if (video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else 0f,
                hasNext = p.hasNextMediaItem(),
                hasPrevious = p.hasPreviousMediaItem(),
                playlistIndex = p.currentMediaItemIndex,
                title = titleAt(p.currentMediaItemIndex),
                chapters = chapters,
                chapterIndex = chapters.indexOfLast { ch -> ch.startMs <= position },
                segments = segments,
                activeSegment = if (skipMode == SkipMode.OFF) null else segments.firstOrNull { s -> s.contains(position, duration) },
            )
        }
    }

    private suspend fun buildMediaItem(index: Int): MediaItem {
        val entry: MediaEntry = request.items[index]
        val external = subtitles[index]
        // Субтитры рядом с локальным файлом подхватываем автоматически.
        if (index == request.startIndex && external.isEmpty()) {
            withContext(Dispatchers.IO) { SubtitleSupport.findSidecars(appContext, entry.uri) }.forEach { uri ->
                val name = uri.lastPathSegment
                external += ExternalSubtitle(uri, name, SubtitleSupport.languageFromName(name))
            }
        }
        val configs = external.map { sub ->
            val normalized = SubtitleSupport.normalize(appContext, sub.uri, request.headers)
            val name = sub.name ?: SubtitleSupport.displayName(appContext, sub.uri)
            MediaItem.SubtitleConfiguration.Builder(normalized)
                .setId(externalId(index, sub))
                .setMimeType(SubtitleSupport.mimeForName(name) ?: MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(sub.language ?: SubtitleSupport.languageFromName(name))
                .setLabel(name?.substringBeforeLast('.'))
                .build()
        }
        // ISO-образ или папка BDMV: открываем диск и играем основной фильм.
        val disc = discs[index] ?: if (DiscSessions.isCandidate(entry.uri)) {
            withContext(Dispatchers.IO) { runCatching { DiscSessions.open(appContext, entry.uri, request.headers) }.getOrNull() }
                ?.also { discs[index] = it }
        } else null
        disc?.disc?.discTitle?.let { if (entry.title.isNullOrBlank()) titles[index] = it }
        val title = withContext(Dispatchers.IO) { titleFor(entry) }
        return MediaItem.Builder()
            .setUri(disc?.titleUri ?: entry.uri)
            .setMediaId(keyAt(index) ?: entry.uri.toString())
            .setMimeType(adaptiveMime(entry))
            .setSubtitleConfigurations(configs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
    }

    private fun externalId(index: Int, sub: ExternalSubtitle): String =
        "$EXTERNAL_SUB_PREFIX$index:${sub.uri.hashCode()}"

    private fun keyAt(index: Int): String? = request.items.getOrNull(index)?.uri?.let(ResumeStore::keyFor)

    private val titles = HashMap<Int, String>()

    private fun titleAt(index: Int): String =
        titles[index] ?: request.items.getOrNull(index)?.let { titleFor(it).also { t -> titles[index] = t } }.orEmpty()

    private fun titleFor(entry: MediaEntry): String =
        entry.title?.takeIf { it.isNotBlank() }
            ?: SubtitleSupport.displayName(appContext, entry.uri)?.substringBeforeLast('.')
            ?: entry.uri.toString()

    /** Передаём MIME только для адаптивных потоков: по нему ExoPlayer выбирает HLS/DASH/SS. */
    private fun adaptiveMime(entry: MediaEntry): String? {
        val mime = entry.mimeType?.lowercase()
        return when {
            mime == null -> if (entry.uri.toString().contains(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else null
            "mpegurl" in mime -> MimeTypes.APPLICATION_M3U8
            "dash" in mime -> MimeTypes.APPLICATION_MPD
            "vnd.ms-sstr" in mime -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    private fun describe(e: PlaybackException): String {
        val t = i18n.current
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> t["error.network"]
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> t["error.http"]
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> t["error.not_found"]
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> t["error.no_permission"]
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> t["error.container"]
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> t["error.decoder"]
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> t["error.drm"]
            else -> t.format("error.generic", e.errorCodeName)
        }
    }
}
