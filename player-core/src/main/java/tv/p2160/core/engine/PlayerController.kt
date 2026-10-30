package tv.p2160.core.engine

import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import tv.p2160.core.avi.AviProbe
import tv.p2160.core.api.Chapter
import tv.p2160.core.bluray.DiscSession
import tv.p2160.core.bluray.DiscSessions
import tv.p2160.core.intro.DetectionResult
import tv.p2160.core.api.PlayerConfig
import tv.p2160.core.intro.IntroDetector
import tv.p2160.core.intro.IntroStart
import tv.p2160.core.api.ExternalAudio
import tv.p2160.core.api.ExternalSubtitle
import tv.p2160.core.api.NowPlaying
import tv.p2160.core.api.SelectedTrack
import tv.p2160.core.api.TrackSelect
import tv.p2160.core.api.stableId
import tv.p2160.core.audio.AudioSupport
import tv.p2160.core.api.PlayerExtensions
import tv.p2160.core.iptv.IptvStore
import tv.p2160.core.api.SegmentType
import tv.p2160.core.api.SkipSegment
import tv.p2160.core.settings.SkipMode
import tv.p2160.core.settings.NightSchedule
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.PlaybackResult
import tv.p2160.core.i18n.I18n
import tv.p2160.core.resume.ResumeEntry
import tv.p2160.core.resume.Covers
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.settings.PlayerSettings
import tv.p2160.core.subtitle.ContentSidecars
import tv.p2160.core.subtitle.SubtitleFolders
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
    /** Название сериала, если ручные отметки применяются ко всем сериям; null - только к этому файлу. */
    val seriesName: String? = null,
    val marks: ManualMarks = ManualMarks(),
    /** Временное ускорение (удержание пальца) включено. */
    val speedBoost: Boolean = false,
    /** Format.id вторых субтитров или null. */
    val secondaryTextId: String? = null,
    val nightMode: Boolean = false,
    /** «Как обычно: …» - дорожки выбраны по привычке. */
    val smartHint: String? = null,
    /** Важные предупреждения о воспроизведении (показываются при старте). */
    val warnings: List<String> = emptyList(),
    /** Прямой эфир: длительность неизвестна или поток помечен как live. */
    val isLive: Boolean = false,
    /** Запрос - телеканалы ([PlaybackRequest.liveTv]): стрелки вверх/вниз переключают каналы. */
    val liveTv: Boolean = false,
    /** Подпись под названием (для каналов - текущая передача из EPG). */
    val subtitle: String? = null,
    /** Задержка звука, мс (> 0 - звук позже картинки). */
    val audioDelayMs: Int = 0,
    /** Таймер сна: сколько осталось до паузы, мс; null - выключен (или стоит «в конце серии»). */
    val sleepRemainingMs: Long? = null,
    /** Таймер сна «в конце серии»: пауза, когда закончится текущий файл. */
    val sleepAtEnd: Boolean = false,
    /** Частота кадров текущего видео (для подбора частоты экрана); -1 - неизвестна. */
    val videoFrameRate: Float = -1f,
    /** Секунды до следующего файла. null - отсчёта нет. */
    val endCountdownSec: Int? = null,
    /** Файл доигран и следующего нет: «Смотреть снова» и «Закрыть». */
    val showEndScreen: Boolean = false,
    /** Кадр погашен после долгого стояния на конце. Любая клавиша возвращает картинку. */
    val endDim: Boolean = false,
    /** Плеер просит активность закрыться. */
    val requestClose: Boolean = false,
    /** Последний файл плейлиста: короткая надпись и возврат в папку. */
    val playlistFinished: Boolean = false,
    /** Сохранённая позиция, если перед стартом надо спросить. */
    val resumePromptMs: Long? = null,
    /** Название следующего файла для карточки. */
    val nextTitle: String? = null,
    /** Файл или URI обложки следующего файла. */
    val nextCoverPath: String? = null,
)

/**
 * Логика воспроизведения, не зависящая от UI: собирает ExoPlayer, восстанавливает
 * позицию/дорожки/скорость, периодически сохраняет прогресс, управляет субтитрами.
 * Все методы вызываются с главного потока.
 */
/** Предел задержки звука в обе стороны, мс. */
const val AUDIO_DELAY_LIMIT_MS = 2_000

/** Живой источник: целевая задержка буфера, пороги и скорости догона. */
private const val LIVE_TARGET_MS = 300L
private const val LIVE_CATCHUP_MS = 800L
private const val LIVE_CATCHUP_SPEED = 1.1f
private const val LIVE_FAR_MS = 2_000L
private const val LIVE_FAR_SPEED = 1.25f

/** Сколько раз повторять открытие сжатого звука (passthrough), прежде чем декодировать самим. */
private const val PASSTHROUGH_RETRIES = 3
private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
private const val SEEK_QUIET_MS = 2_000L
private const val MAX_STALL_RECOVERIES = 2
private const val PLAYER_LOG_TAG = "Player2160"

@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val request: PlaybackRequest,
    /** Настройка движка; по умолчанию - глобальная [tv.p2160.core.api.Player2160.config]. */
    private val config: PlayerConfig = PlayerExtensions.config,
) {
    private val appContext = context.applicationContext
    private val settings = PlayerSettings.get(appContext)
    private val store = ResumeStore.get(appContext)
    private val i18n = I18n.get(appContext)
    private val segmentStore = SegmentStore.get(appContext)
    private val analyzer = MediaAnalyzer(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Когда последний раз повторяли открытие сжатого звука после ошибки выхода. */
    private var passthroughRetryAt = 0L
    private var passthroughRetries = 0
    /** Повтор декодера уже поставлен в очередь: дорожки на этот миг ещё без видео, ошибку не показываем. */
    private var videoRetryPending = false
    /** onTracksChanged пришёл, пока шёл повтор prepare: проверку видео нельзя пропустить. */
    private var unsupportedCheckDeferred = false
    /** Звуковые часы AVI+MP3 не идут: не больше двух попыток на файл, дальше ошибка. */
    private var ffmpegAudioTried = false
    private var audioDiscontinuityCount = 0
    private var audioDiscontinuitySince = 0L
    private var stallItem = -1
    private var stallRecoveries = 0
    private var stallStopped = false
    private var watchdogArmedAt = 0L
    private var watchdogBaseFrames = -1
    private var watchdogBaseInput = -1
    /** После перемотки кадры не считаются зависанием. */
    private var seekQuietUntil = 0L
    private var videoCounters: DecoderCounters? = null
    /** Смена режима дисплея (частоту под видео переключает система или мы) - сбои звука после неё временные. */
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            // Событие приходит и от яркости/состояния - интересует только смена режима (частоты, разрешения).
            val mode = displayMode(displayId)
            if (mode != lastDisplayMode) {
                lastDisplayMode = mode
                onDisplaySwitching()
            }
        }
    }
    private var lastDisplayMode = displayMode(android.view.Display.DEFAULT_DISPLAY)

    private fun displayMode(displayId: Int): Int =
        appContext.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(displayId)?.mode?.modeId ?: -1
    /** Таймер сна: момент паузы (SystemClock.elapsedRealtime) или 0 - выключен. */
    private var sleepAtMs = 0L
    private var sleepFadeJob: Job? = null
    private var coverJob: Job? = null

    /** Все элементы - живые источники (камера по RTSP и т.п.): режим низкой задержки. */
    private val lowLatency = request.items.isNotEmpty() && request.items.all { it.uri.scheme?.lowercase() in PlayerFactory.LOW_LATENCY_SCHEMES }

    private val built = PlayerFactory.build(appContext, settings.current, request.headers, config, lowLatency = lowLatency)
    val player: ExoPlayer get() = built.player
    /** Реплики вторых субтитров - рисуются отдельным слоем сверху. */
    val secondaryCues get() = built.secondarySubtitles.cues

    private val _state = MutableStateFlow(PlayerUiState(playlistSize = request.items.size, liveTv = request.liveTv, nightMode = settings.current.nightModeAt(NightSchedule.nowMinute())))
    /** Пользователь переключил ночной звук вручную в этом просмотре - расписание больше не вмешивается. */
    private var nightOverridden = false
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** Внешние субтитры по индексу плейлиста (растут при ручном добавлении и из истории). */
    private val subtitles: MutableList<MutableList<ExternalSubtitle>> =
        request.items.map { it.subtitles.toMutableList() }.toMutableList()
    /** Автопоиск субтитров рядом с видео уже выполнен для этого пункта. */
    private val sidecarsScanned = BooleanArray(request.items.size)
    /** Внешнее аудио по индексу плейлиста: из запроса, автопоиска и ручной загрузки. */
    private val audios: MutableList<MutableList<ExternalAudio>> =
        request.items.map { it.audio.toMutableList() }.toMutableList()
    private val audioSidecarsScanned = BooleanArray(request.items.size)
    /** URI, которые в этом запуске уже сломали воспроизведение. Сайдкары при следующем открытии ищутся снова. */
    private val rejectedAudio = Array(request.items.size) { HashSet<String>() }
    private val warnedExternal = BooleanArray(request.items.size)
    /** Предпочтение, которое переносится на следующую серию этого запуска. */
    private var sessionAudio: CarryHint? = null
    private var sessionText: CarryHint? = null
    private var pendingAudioHint: CarryHint? = null
    private var pendingTextHint: CarryHint? = null
    private var itemsOpened = 0
    /** Сдвиг выбранной внешней дорожки, мс. Плюсуется к задержке пользователя. */
    private var appliedAudioOffsetMs = 0L
    private var appliedTextOffsetMs = 0L
    private val externalAudioRecoveries = IntArray(request.items.size)

    private val trackPrefs = TrackPreferences.get(appContext)
    /** Применить привычку при первом известном списке дорожек нового файла. */
    private var pendingSmart = false
    /** После ручного выбора дождаться обновления дорожек и записать привычку. */
    private var recordChoiceOnTracks = false

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
    /** Вступление/титры, найденные по звуку, - самый низкий приоритет. */
    private var autoSegments: List<SkipSegment> = emptyList()
    /** Найденные по звуку отрезки с низкой уверенностью: только кнопка, без автопропуска. */
    private val lowConfidence = mutableSetOf<SkipSegment>()
    private var introJob: Job? = null
    /** Элемент, для которого поиск вступления уже разрешён готовностью плеера. -1 - ещё нет. */
    private var introItem = -1
    private var introAttempts = 0
    private var decoders = ActiveDecoders()

    /** Какие декодеры реально выбраны - для сводки по файлу. */
    private val analytics = object : AnalyticsListener {
        override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            videoCounters = decoderCounters
        }

        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            decoders = decoders.copy(video = decoderName)
            Log.i(PLAYER_LOG_TAG, "video decoder $decoderName")
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            decoders = decoders.copy(audio = decoderName, audioPassthrough = false)
            Log.i(PLAYER_LOG_TAG, "audio decoder $decoderName")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            val gap = audioSinkError as? AudioSink.UnexpectedDiscontinuityException
            if (gap != null) {
                Log.w(
                    PLAYER_LOG_TAG,
                    "audio discontinuity actualUs=${gap.actualPresentationTimeUs} expectedUs=${gap.expectedPresentationTimeUs} audio=${decoders.audio} video=${decoders.video}",
                )
            }
            if (gap == null && !isTimestampDiscontinuity(audioSinkError)) return
            scope.launch { noteAudioDiscontinuity() }
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            Log.i(PLAYER_LOG_TAG, "first video frame audio=${decoders.audio} video=${decoders.video}")
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            val enc = audioTrackConfig.encoding
            val pcm = enc == C.ENCODING_PCM_16BIT || enc == C.ENCODING_PCM_FLOAT || enc == C.ENCODING_PCM_24BIT || enc == C.ENCODING_PCM_32BIT || enc == C.ENCODING_PCM_8BIT
            decoders = decoders.copy(audioPassthrough = !pcm)
        }
    }
    private var warningsShownFor = -1
    /** Открытые диски Blu-ray по индексу плейлиста. */
    private val discs = HashMap<Int, DiscSession>()
    private var speedBeforeBoost: Float? = null
    /**
     * Скорость, выбранная пользователем или восстановленная из истории. Временные 2× (удержание)
     * и догон живого края её не меняют - в историю пишется она, а не текущая скорость плеера.
     */
    private var playbackSpeed = 1f
    /** Сейчас играет ускорение догона живого края, а не [playbackSpeed]. */
    private var liveCatchup = false

    private val statsCollector = StatsCollector(
        built.player,
        built.allocator,
        built.bufferTargetBytes.toLong(),
        decoders = { decoders },
        tunneling = { built.player.trackSelectionParameters.let { it is androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters && it.tunnelingEnabled } },
        frameRate = { _state.value.videoFrameRate },
    )
    /** Частота кадров, вычисленная по меткам кадров (если в заголовке её нет). */
    @Volatile private var detectedFrameRate = -1f
    private val frameRateProbe = FrameRateProbe { fps ->
        detectedFrameRate = fps
        scope.launch { refresh() }
    }
    /** Живая статистика для слоя поверх видео; null - сбор выключен ([setStatsEnabled]). */
    val stats: StateFlow<PlaybackStats?> get() = statsCollector.stats

    /** UI переключает режим экрана (частота под видео): ближайшие 12 с сбои открытия звука временные. */
    fun onDisplaySwitching() {
        built.passthrough.displaySwitchUntil = SystemClock.elapsedRealtime() + 12_000
    }

    /** Включить/выключить сбор статистики (опрос памяти раз в 2 с - только пока слой виден). */
    fun setStatsEnabled(enabled: Boolean) {
        if (enabled) statsCollector.start(scope) else statsCollector.stop()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()

        override fun onTracksChanged(tracks: Tracks) {
            applyPendingSelections(tracks)
            noteUnsupportedExternal(tracks)
            if (recordChoiceOnTracks) {
                recordChoiceOnTracks = false
                rememberChoice(tracks)
            }
            logAviTracks(tracks)
            reportMissingAviVideo(tracks)
            reportUnsupportedVideo(tracks)
            refresh()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && undoFailedAdvance()) return
            setSecondarySubtitle(null)
            onItemStarted(player.currentMediaItemIndex, explicitStart = null)
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                armSeekQuiet()
            }
            // Переход к следующему элементу: сохраняем прогресс предыдущего как досмотренный.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && old.mediaItemIndex != new.mediaItemIndex) {
                val window = player.currentTimeline.takeIf { old.mediaItemIndex < it.windowCount }
                    ?.getWindow(old.mediaItemIndex, androidx.media3.common.Timeline.Window())
                val duration = window?.durationMs?.takeIf { it != C.TIME_UNSET } ?: return
                saveFor(old.mediaItemIndex, duration, duration)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) resetWatchdogClock()
            if (playbackState == Player.STATE_READY) {
                holdFailedItem = -1
                scheduleIntro(player.currentMediaItemIndex)
            }
            if (playbackState == Player.STATE_ENDED) {
                if (holdFailedItem >= 0 && _state.value.error != null) return
                onNaturalEnd()
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Пауза и стоп не должны досчитывать таймаут «нет кадра».
            if (!playWhenReady) resetWatchdogClock()
            // pauseAtEndOfMediaItems: не последний файл не даёт STATE_ENDED, только эту причину.
            // Сон «в конце серии» обрабатывается внутри onNaturalEnd.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (holdFailedItem >= 0 && _state.value.error != null) return
                onNaturalEnd()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Выход не открыл сжатый поток (AC3/DTS «на ресивер»). Сразу после переключения звука или смены
            // режима экрана (HDMI заново согласует звук с ресивером) это бывает временно: Realtek отвечает
            // createTrack -38 несколько секунд. Повторяем до 3 раз через 2 с (и без счёта, пока меняется режим
            // экрана). Не вышло - ТВ действительно не умеет: декодируем сами, без ошибки.
            if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED && !built.passthrough.disabled) {
                val now = SystemClock.elapsedRealtime()
                if (now - passthroughRetryAt > 30_000) passthroughRetries = 0
                val switching = now < built.passthrough.displaySwitchUntil
                if (switching || passthroughRetries < PASSTHROUGH_RETRIES) {
                    if (!switching && passthroughRetries == 0) {
                        val message = i18n.current["player.passthrough_retry"]
                        _state.update { it.copy(warnings = (it.warnings + message).distinct()) }
                    }
                    if (!switching) passthroughRetries++
                    passthroughRetryAt = now
                    val play = player.playWhenReady
                    scope.launch {
                        delay(2_000)
                        player.prepare()
                        player.playWhenReady = play
                    }
                    return
                }
                built.passthrough.disabled = true
                built.passthrough.failed = true
                player.prepare()
                player.play()
                return
            }
            // Туннельный режим на этом устройстве не работает (ошибка декодера/вывода видео) - выключаем насовсем и продолжаем.
            val params = player.trackSelectionParameters
            if (params is androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters && params.tunnelingEnabled &&
                error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED
            ) {
                player.trackSelectionParameters = params.buildUpon().setTunnelingEnabled(false).build()
                settings.update { it.copy(tunneling = false) }
                _state.update { it.copy(warnings = it.warnings + i18n.current["player.tunneling_off"]) }
                val position = player.currentPosition
                player.prepare()
                player.seekTo(position)
                player.play()
                return
            }
            if (retryVideoDecoder(error)) return
            if (recoverExternalAudio(error)) return
            if (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK && recoverAviStall()) return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            cancelIntro()
            introAttempts = IntroStart.MAX_ATTEMPTS
            // Ошибка контейнера или декодера не должна открывать следующий пункт плейлиста.
            holdFailedItem = player.currentMediaItemIndex
            _state.update { it.copy(error = describe(error)) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = refresh()
    }

    init {
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        player.addAnalyticsListener(statsCollector.analytics)
        player.addAudioOffloadListener(statsCollector.offloadListener)
        player.setVideoFrameMetadataListener(frameRateProbe)
        appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        player.pauseAtEndOfMediaItems = true
        built.passthrough.audioDelayUs = settings.current.audioDelayMs * 1000L
        _state.update { it.copy(audioDelayMs = settings.current.audioDelayMs) }
        PlaybackSessions.attach(appContext, this)
        scope.launch { start() }
    }

    /**
     * Плеер ушёл с экрана, но звук продолжается (фоновое воспроизведение): видео не декодируем,
     * чтобы не тратить батарею; при возврате дорожка включается снова.
     */
    fun setInBackground(background: Boolean) {
        val params = player.trackSelectionParameters
        if (params.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == background) return
        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, background).build()
    }

    private suspend fun start() {
        request.items.forEach { item ->
            val key = ResumeStore.keyFor(item.uri)
            item.subtitles.forEach { sub ->
                SubtitleSupport.persistReadPermission(appContext, sub.uri)
                store.upsertExternalSubtitle(key, sub)
            }
            item.audio.forEach { audio ->
                SubtitleSupport.persistReadPermission(appContext, audio.uri)
                store.upsertExternalAudio(key, audio)
            }
        }
        val mediaItems = request.items.indices.map { buildMediaItem(it) }
        val startIndex = request.startIndex.coerceIn(mediaItems.indices)
        player.setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
        onItemStarted(startIndex, explicitStart = request.startPositionMs, opening = true)
        player.prepare()
        player.playWhenReady = _state.value.resumePromptMs == null
        startProgressLoop()
    }

    /** Восстанавливает позицию, скорость и дорожки для текущего элемента плейлиста. */
    private fun onItemStarted(index: Int, explicitStart: Long?, opening: Boolean = false) {
        cancelIntro()
        introItem = -1
        introAttempts = 0
        completed = false
        naturalEndHandled = false
        endHoldSince = 0L
        detectedFrameRate = -1f
        frameRateProbe.reset()
        audioDiscontinuityCount = 0
        resetWatchdogClock()
        if (index != stallItem) {
            stallItem = index
            stallRecoveries = 0
            stallStopped = false
            if (ffmpegAudioTried && settings.current.decoder != tv.p2160.core.settings.DecoderPreference.FFMPEG) {
                built.decoderManager.selectAudioDecoder(io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode.AUTO)
            }
            ffmpegAudioTried = false
        }
        // Телеканалы: ни продолжения с места, ни сохранённых дорожек - сразу эфир.
        val entry = if (request.liveTv || !config.restoreFromHistory) null else keyAt(index)?.let(store::get)
        val s = settings.current

        val resumeFrom = when {
            request.liveTv -> null
            explicitStart != null -> explicitStart.takeIf { it > 0 }
            !s.autoResume || entry == null || entry.finished -> null
            entry.positionMs > 5_000 -> entry.positionMs
            else -> null
        }
        resumeFrom?.let { player.seekTo(index, it) }

        val speed = playbackSpeedFor(entry?.speed, s.defaultSpeed, request.liveTv)
        playbackSpeed = speed
        liveCatchup = false
        player.playbackParameters = PlaybackParameters(speed)
        appliedAudioOffsetMs = 0L
        appliedTextOffsetMs = 0L
        val userTextDelay = entry?.subtitleDelayMs ?: 0L

        pendingRestore = entry
        // Своего сохранённого выбора у файла нет - попробуем привычку (сериал / набор языков).
        pendingSmart = entry == null && settings.current.smartTracks && config.restoreFromHistory
        pendingExternalSelect = null
        val openedBefore = itemsOpened > 0
        itemsOpened++
        val explicitAudio = explicitAudioHint(index)
        val explicitText = explicitTextHint(index)
        pendingAudioHint = if (openedBefore && sessionAudio != null) sessionAudio else explicitAudio
        pendingTextHint = if (openedBefore && sessionText != null) sessionText else explicitText
        if (sessionAudio == null) sessionAudio = explicitAudio
        if (sessionText == null) sessionText = explicitText
        // Явные субтитры без textSelect: старый флаг select.
        if (pendingTextHint == null) {
            subtitles.getOrNull(index)?.firstOrNull { it.select }?.let { pendingExternalSelect = externalId(index, it) }
        }

        val offer = if (opening) {
            resumeOfferMs(
                autoResume = s.autoResume,
                explicitStart = explicitStart,
                savedMs = resumeFrom,
                finished = entry?.finished == true,
                live = request.liveTv || isLiveSource(request.items[index].uri),
            )
        } else null
        val next = request.items.getOrNull(index + 1)
        _state.update {
            it.copy(
                resumedFromMs = if (offer != null) null else resumeFrom,
                resumePromptMs = offer,
                error = null,
                title = titleAt(index),
                subtitle = liveSubtitle(index),
                nextTitle = next?.title?.takeIf { t -> t.isNotBlank() } ?: next?.uri?.lastPathSegment,
                nextCoverPath = if (next != null) previewSource(index + 1) else null,
                endCountdownSec = null,
                showEndScreen = false,
                playlistFinished = false,
                endDim = false,
                requestClose = false,
                subtitleDelayMs = userTextDelay,
            )
        }
        applyTextClock()
        applyAudioClock()
        applyPendingSelections(player.currentTracks)
        // Главы и поиск вступления в эфире бессмысленны (и FFmpeg повис бы на бесконечном потоке).
        if (!request.liveTv) loadChaptersAndSegments(index)
        if (!request.liveTv && config.saveHistory) findCover(index)
    }

    /**
     * Обложка для «Продолжить просмотр» ([Covers]): встроенная, переданная источником или картинка рядом
     * с файлом. Ищем один раз на запись и через 10 с - не мешаем буферизации.
     */
    private fun findCover(index: Int) {
        coverJob?.cancel()
        val key = keyAt(index) ?: return
        val entry = request.items.getOrNull(index) ?: return
        if (Covers.get(appContext, key) != null) return
        coverJob = scope.launch {
            delay(10_000)
            if (player.currentMediaItemIndex != index) return@launch
            Covers.find(appContext, key, entry.uri, entry.artworkUri, player.mediaMetadata.artworkData, request.headers)
        }
    }

    /** Текущая передача канала из телегида (свой [PlayerExtensions.liveGuide] или встроенный IPTV). */
    private fun liveSubtitle(index: Int): String? {
        if (!request.liveTv) return null
        val entry = request.items.getOrNull(index) ?: return null
        val guide = PlayerExtensions.liveGuide ?: IptvStore.get(appContext)
        return runCatching { guide.describe(entry, System.currentTimeMillis()) }.getOrNull()?.ifBlank { null }
    }

    /** Живой поток (камера по RTSP и т.п.): ни глав, ни поиска вступления - лишнее подключение к источнику. */
    private fun isLiveSource(uri: Uri): Boolean = uri.scheme?.lowercase() in PlayerFactory.LOW_LATENCY_SCHEMES

    private fun loadChaptersAndSegments(index: Int) {
        val entry = request.items.getOrNull(index) ?: return
        chapters = emptyList()
        autoSkipped.clear()
        autoSegments = emptyList()
        lowConfidence.clear()
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
                // Главы диска - из плейлиста (FFmpeg в ISO не заглянет).
                val starts = d.title.chapters
                starts.mapIndexed { i, start -> Chapter(null, start, starts.getOrElse(i + 1) { d.title.durationMs }) }
            } ?: if (config.readChapters && !isLiveSource(entry.uri)) {
                runCatching { analyzer.chapters(entry.uri, request.headers) }.getOrDefault(emptyList())
            } else emptyList()
            if (player.currentMediaItemIndex != index) return@launch
            chapters = found
            // Явно переданные отрезки (Intent/медиасервер) важнее найденных по главам.
            val explicitTypes = entry.segments.map { it.type }.toSet()
            baseSegments = entry.segments + SegmentDetector.fromChapters(found).filter { it.type !in explicitTypes }
            refresh()
        }
    }

    private fun cancelIntro() {
        introJob?.cancel()
        introJob = null
    }

    /** Поиск вступления - только после первого успешного открытия (STATE_READY), не до него. */
    private fun scheduleIntro(index: Int) {
        if (player.playbackState != Player.STATE_READY || player.playerError != null) return
        if (introItem == index && (introJob?.isActive == true || introAttempts >= IntroStart.MAX_ATTEMPTS)) return
        if (introItem != index) {
            introItem = index
            introAttempts = 0
        }
        launchIntro(index)
    }

    /**
     * Поиск вступления/титров по звуку: сравниваем с соседними сериями того же сериала.
     * Не запускаем, если отрезки уже известны (главы, Intent, ручные отметки) или это диск Blu-ray.
     * Ошибка открытия не зацикливается: несколько попыток с паузой, затем стоп.
     */
    private fun launchIntro(index: Int) {
        if (request.liveTv || !config.introDetection || discs[index] != null) {
            introAttempts = IntroStart.MAX_ATTEMPTS
            return
        }
        if (!IntroStart.allowed(player.playbackState == Player.STATE_READY, player.playerError != null, introAttempts)) return
        val entry = request.items.getOrNull(index) ?: return
        if (isLiveSource(entry.uri)) {
            introAttempts = IntroStart.MAX_ATTEMPTS
            return
        }
        val title = titleAt(index)
        val fileName = SubtitleSupport.displayName(appContext, entry.uri) ?: title
        val series = SegmentDetector.seriesKey(fileName) ?: SegmentDetector.seriesKey(title)
        if (series == null) {
            introAttempts = IntroStart.MAX_ATTEMPTS
            return
        }
        val known = (baseSegments + marks.toSegments(Long.MAX_VALUE / 4)).map { it.type }.toSet()
        if (SegmentType.INTRO in known && SegmentType.CREDITS in known) {
            introAttempts = IntroStart.MAX_ATTEMPTS
            return
        }
        val detector = IntroDetector.get(appContext)
        detector.cached(entry.uri)?.let {
            applyDetection(it, known)
            introAttempts = IntroStart.MAX_ATTEMPTS
            return
        }
        val siblings = IntroDetector.pickSiblings(request.items.indices.toList(), index)
            .map { request.items[it] }
            .filter { sib ->
                val name = SubtitleSupport.displayName(appContext, sib.uri) ?: sib.title
                SegmentDetector.seriesKey(name) == series
            }
            .map { it.uri }
        introJob?.cancel()
        val attempt = introAttempts
        introJob = scope.launch {
            delay(IntroStart.delayMs(attempt))
            if (!isActive) return@launch
            if (!IntroStart.allowed(player.playbackState == Player.STATE_READY, player.playerError != null, introAttempts)) return@launch
            if (player.currentMediaItemIndex != index) return@launch
            introAttempts++
            val language = TrackLabels.collect(player.currentTracks, C.TRACK_TYPE_AUDIO, i18n.current)
                .firstOrNull { it.selected }?.language
            val pass = runCatching {
                detector.detectReporting(
                    current = entry.uri,
                    siblings = siblings,
                    headers = request.headers,
                    seriesKey = series,
                    durationMs = player.duration.takeIf { it > 0 } ?: -1,
                    preferredLanguage = language,
                )
            }.getOrNull()
            if (player.currentMediaItemIndex != index || player.playerError != null) return@launch
            if (pass == null || !pass.sourceOpened) {
                if (IntroStart.retryAfter(sourceOpened = false, attempts = introAttempts)) launchIntro(index)
                return@launch
            }
            applyDetection(pass.result, known)
            introAttempts = IntroStart.MAX_ATTEMPTS
        }
    }

    private fun applyDetection(result: DetectionResult, known: Set<SegmentType>) {
        val found = result.segments.filter { it.type !in known }
        autoSegments = found
        lowConfidence.clear()
        found.forEach { s ->
            val confidence = if (s.type == SegmentType.INTRO) result.introConfidence else result.creditsConfidence
            if (confidence < 0.6f) lowConfidence += s
        }
        refresh()
    }

    private fun currentSegments(durationMs: Long): List<SkipSegment> {
        val manual = marks.toSegments(durationMs)
        val manualTypes = manual.map { it.type }.toSet()
        val base = baseSegments.filter { it.type !in manualTypes } + manual
        val baseTypes = base.map { it.type }.toSet()
        return (base + autoSegments.filter { it.type !in baseTypes }).sortedBy { it.startMs }
    }

    private fun applyPendingSelections(tracks: Tracks) {
        if (tracks.isEmpty) return
        val index = player.currentMediaItemIndex
        var pinAudio = false
        var pinText = false
        pendingAudioHint?.let { hint ->
            val options = TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current)
            val picked = TrackCarry.pick(hint, audioCarry(options, index))
            if (picked != null) pinAudio = selectTrack(options[picked], user = false)
            pendingAudioHint = null
        }
        pendingTextHint?.let { hint ->
            if (hint.off) {
                disableText(user = false)
                pinText = true
            } else {
                val options = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current)
                val picked = TrackCarry.pick(hint, textCarry(options, index))
                if (picked != null) {
                    selectTrack(options[picked], user = false)
                    pinText = true
                }
            }
            pendingTextHint = null
        }
        if (pendingSmart && !pinAudio && !pinText) {
            pendingSmart = false
            applySmartChoice(tracks)
        } else if (pendingSmart) {
            pendingSmart = false
        }
        var pinnedExternal = pinText
        pendingExternalSelect?.let { id ->
            if (!pinText) {
                val option = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current).firstOrNull {
                    val formatId = tracks.groups[it.groupIndex].getTrackFormat(it.trackIndex).id
                    formatId == id || formatId?.endsWith(id) == true
                }
                if (option != null) {
                    selectTrack(option, user = false)
                    pendingExternalSelect = null
                    pinnedExternal = true
                }
            } else {
                pendingExternalSelect = null
            }
        }
        val entry = pendingRestore ?: return
        pendingRestore = null

        val audio = TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current)
        if (!pinAudio) {
            (audio.firstOrNull { entry.audioLabel != null && it.label == entry.audioLabel }
                ?: audio.firstOrNull { entry.audioLanguage != null && it.language == entry.audioLanguage })
                ?.takeUnless { it.selected }?.let { selectTrack(it, user = false, seed = sessionAudio == null) }
        }

        // Явный внешний файл важнее сохранённого «выключены» и языка встроенной дорожки.
        // Пока дорожка ещё не пришла, pendingExternalSelect не даёт истории её выключить.
        if (!pinnedExternal && pendingExternalSelect == null) {
            if (entry.textDisabled) {
                disableText(user = false)
                if (sessionText == null) sessionText = CarryHint(off = true)
            } else {
                val text = TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current)
                (text.firstOrNull { entry.textLabel != null && it.label == entry.textLabel }
                    ?: text.firstOrNull { entry.textLanguage != null && it.language == entry.textLanguage })
                    ?.takeUnless { it.selected }?.let { selectTrack(it, user = false, seed = sessionText == null) }
            }
        }
    }

    // region Привычки выбора дорожек

    private fun candidates(tracks: Tracks, type: Int): List<Pair<TrackOption, TrackCandidate>> =
        TrackLabels.collect(tracks, type, i18n.current).mapIndexed { i, o ->
            val f = tracks.groups[o.groupIndex].getTrackFormat(o.trackIndex)
            o to TrackCandidate(i, f.language, f.label, f.channelCount.coerceAtLeast(0), f.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
        }

    private fun currentSeriesKey(): String? {
        val entry = request.items.getOrNull(player.currentMediaItemIndex) ?: return null
        val name = SubtitleSupport.displayName(appContext, entry.uri) ?: entry.title
        return SegmentDetector.seriesKey(name) ?: SegmentDetector.seriesKey(entry.title)
    }

    private fun applySmartChoice(tracks: Tracks) {
        val audio = candidates(tracks, C.TRACK_TYPE_AUDIO)
        val text = candidates(tracks, C.TRACK_TYPE_TEXT)
        val ctx = TrackRules.contextKey(audio.map { it.second })
        val choice = trackPrefs.forSeries(currentSeriesKey()) ?: trackPrefs.forContext(ctx) ?: return

        val pickedAudio = TrackRules.pickAudio(choice, audio.map { it.second })?.let { c -> audio.first { it.second == c }.first }
        pickedAudio?.takeUnless { it.selected }?.let { selectTrack(it, user = false) }

        var subsLabel: String? = null
        if (choice.textLanguage == null) {
            if (text.isNotEmpty()) disableText(user = false)
        } else {
            val pickedText = TrackRules.pickText(choice, text.map { it.second })?.let { c -> text.first { it.second == c }.first }
            pickedText?.let { selectTrack(it, user = false); subsLabel = it.label }
        }
        if (pickedAudio != null || subsLabel != null) {
            val t = i18n.current
            val parts = listOfNotNull(pickedAudio?.label, subsLabel?.let { t.format("smart.subs", it) } ?: if (choice.textLanguage == null && text.isNotEmpty()) t["smart.no_subs"] else null)
            _state.update { it.copy(smartHint = t.format("smart.applied", parts.joinToString(" · "))) }
        }
    }

    private fun rememberChoice(tracks: Tracks) {
        if (!settings.current.smartTracks) return
        val audio = candidates(tracks, C.TRACK_TYPE_AUDIO)
        val text = candidates(tracks, C.TRACK_TYPE_TEXT)
        val selectedAudio = audio.firstOrNull { it.first.selected }?.second ?: return
        val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val selectedText = if (textOff) null else text.firstOrNull { it.first.selected }?.second
        val series = currentSeriesKey()
        val ctx = TrackRules.contextKey(audio.map { it.second })
        var textLanguage = if (textOff) null else TrackRules.normalizeLang(selectedText?.language)
        var textForced = !textOff && selectedText?.forced == true
        // Выбранный внешний файл без кода языка нельзя записать как «субтитры выключены»:
        // привычка сериала иначе погасит дорожку при следующем открытии.
        if (!textOff && selectedText != null && textLanguage == null) {
            val prev = trackPrefs.forSeries(series) ?: trackPrefs.forContext(ctx) ?: return
            textLanguage = prev.textLanguage
            textForced = prev.textForced
        }
        val choice = TrackChoice(
            audioLanguage = TrackRules.normalizeLang(selectedAudio.language),
            audioHint = TrackRules.hintOf(selectedAudio.label),
            textLanguage = textLanguage,
            textForced = textForced,
        )
        trackPrefs.remember(series, ctx, choice)
    }

    fun dismissSmartHint() = _state.update { it.copy(smartHint = null) }

    // endregion

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
        armSeekQuiet()
        player.seekTo(positionMs.coerceIn(0, duration))
        refresh()
    }

    fun seekBy(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun next() {
        if (request.liveTv) return switchChannel(1)
        if (player.hasNextMediaItem()) { saveProgress(); player.seekToNextMediaItem() }
    }

    /** Карточка «Дальше»: следующий файл и сразу воспроизведение. */
    fun playNextNow() {
        if (player.hasNextMediaItem()) {
            saveProgress()
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun previous() {
        if (request.liveTv) return switchChannel(-1)
        if (player.currentPosition > 5_000 || !player.hasPreviousMediaItem()) player.seekTo(0)
        else { saveProgress(); player.seekToPreviousMediaItem() }
    }

    /** Переключение канала по кругу (для [PlaybackRequest.liveTv]); после ошибки поток открывается заново. */
    fun switchChannel(delta: Int) {
        val count = player.mediaItemCount
        if (count < 2) return
        val target = Math.floorMod(player.currentMediaItemIndex + delta, count)
        player.seekToDefaultPosition(target)
        if (player.playbackState == Player.STATE_IDLE || player.playerError != null) player.prepare()
        player.playWhenReady = true
        _state.update { it.copy(error = null) }
        refresh()
    }

    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(0.25f, 4f)
        playbackSpeed = value
        liveCatchup = false
        if (speedBeforeBoost != null) speedBeforeBoost = value
        else player.playbackParameters = PlaybackParameters(value)
        saveProgress()
        refresh()
    }

    /** Выбор дорожки пользователем (запоминается как привычка). */
    fun select(option: TrackOption) {
        selectTrack(option, user = true)
    }

    /** @return false, если внешний звук устройство не декодирует: выбор не применяется. */
    private fun selectTrack(option: TrackOption, user: Boolean, seed: Boolean = false): Boolean {
        if (option.type == C.TRACK_TYPE_AUDIO && option.external && !option.supported) {
            warnExternalCodec(option.label)
            clearRememberedExternal(option)
            if (option.selected) {
                val embedded = TrackLabels.collect(player.currentTracks, C.TRACK_TYPE_AUDIO, i18n.current)
                    .firstOrNull { !it.external && it.supported }
                if (embedded != null) selectTrack(embedded, user = false)
            }
            return false
        }
        if (user && (option.type == C.TRACK_TYPE_AUDIO || option.type == C.TRACK_TYPE_TEXT)) recordChoiceOnTracks = true
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .build()
        if (option.type == C.TRACK_TYPE_AUDIO) {
            appliedAudioOffsetMs = audioOffset(option)
            applyAudioClock()
            if (user || (seed && sessionAudio == null)) sessionAudio = hintForAudio(option)
            val index = player.currentMediaItemIndex
            val picked = if (option.external) audioFor(option) else null
            markAudioSelected(index, picked?.uri)
            if (user || option.external) {
                keyAt(index)?.let { store.setExternalAudioSelected(it, picked?.uri?.toString()) }
            }
            return true
        }
        if (option.type != C.TRACK_TYPE_TEXT) return true
        val index = player.currentMediaItemIndex
        val key = keyAt(index) ?: return true
        val uri = externalUriFor(option)
        // Автовыбор встроенной дорожки из истории не стирает запомненный внешний файл:
        // его мог только что попросить пользователь, а дорожки ещё не совпали.
        if (user || uri != null) {
            store.setExternalSubtitleSelected(key, uri?.toString())
            markExternalSelected(index, uri)
        }
        if (uri != null) enableRememberedText(key, option.language)
        appliedTextOffsetMs = textOffset(option)
        applyTextClock()
        if (user || (seed && sessionText == null)) sessionText = hintForText(option)
        return true
    }

    /** Сбрасывает ручной выбор видеодорожки - плеер снова выбирает качество сам (важно для HLS/DASH). */
    fun autoVideo() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    }

    /** Пользователь выключил субтитры (запоминается как привычка). */
    fun disableSubtitles() = disableText(user = true)

    private fun disableText(user: Boolean) {
        if (user) recordChoiceOnTracks = true
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        appliedTextOffsetMs = 0
        applyTextClock()
        if (user) sessionText = CarryHint(off = true)
        if (!user) return
        val index = player.currentMediaItemIndex
        keyAt(index)?.let { store.setExternalSubtitleSelected(it, null) }
        markExternalSelected(index, null)
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

    /** «Ночной звук» на лету: при включении звук переходит на декодирование, при выключении - снова на ресивер. */
    fun setNightMode(enabled: Boolean) {
        nightOverridden = true
        applyNight(enabled)
    }

    private fun applyNight(enabled: Boolean) {
        built.night.enabled = enabled
        // В offload обработка звука не работает - на время ночного звука offload выключаем.
        val offload = settings.current.audioOffload && !enabled
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(offloadPreferences(offload)).build()
        var reinit = false
        // Обход декодера (passthrough) следует за ночным звуком в обе стороны:
        // включили - декодируем, чтобы обработка заработала; выключили - снова отдаём ресиверу многоканал.
        // Если выход уже не смог принять сжатый поток (ТВ с AUDIO_TRACK_INIT_FAILED), остаёмся на декодировании.
        val decode = enabled || built.passthrough.failed
        // Вернуть passthrough можно всегда (даже если сейчас звук декодируется, например серию запустили
        // с ночным звуком); перейти на декодирование нужно, только если звук действительно шёл в обход декодера.
        if (built.passthrough.disabled != decode && (!decode || decoders.audioPassthrough)) {
            built.passthrough.disabled = decode
            reinit = true
        }
        // Сведение в стерео тоже только на время ночного звука: после выключения AAC 5.1/FLAC
        // и другие всегда декодируемые дорожки возвращаются в многоканал.
        if (built.night.downmixToStereo != enabled) {
            built.night.downmixToStereo = enabled
            reinit = true
        }
        // Видео не трогаем - звук прерывается меньше чем на секунду.
        if (reinit) reinitAudio()
        _state.update { it.copy(nightMode = enabled) }
    }

    /** Переинициализация аудиорендерера: выключить и снова включить аудио-дорожку. */
    private fun reinitAudio() {
        val params = player.trackSelectionParameters
        player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        player.trackSelectionParameters = params
    }

    /** Расписание изменили в плеере: снимаем ручное переключение и применяем сразу. */
    fun onNightScheduleChanged() {
        nightOverridden = false
        applyNightSchedule()
    }

    /** Раз в минуту: наступило/закончилось время по расписанию - переключаем на лету. */
    private fun applyNightSchedule() {
        if (nightOverridden) return
        val wanted = settings.current.nightModeAt(NightSchedule.nowMinute())
        if (wanted != built.night.enabled) applyNight(wanted)
    }

    /** Задержка звука, мс (> 0 - звук позже картинки); сохраняется в настройках - общая для всех файлов. */
    fun setAudioDelay(ms: Int) {
        val value = ms.coerceIn(-AUDIO_DELAY_LIMIT_MS, AUDIO_DELAY_LIMIT_MS)
        settings.update { it.copy(audioDelayMs = value) }
        _state.update { it.copy(audioDelayMs = value) }
        applyAudioClock()
    }

    /**
     * Таймер сна: через [minutes] минут звук плавно затихает и плеер встаёт на паузу.
     * null или 0 - выключить.
     */
    fun setSleepTimer(minutes: Int?) {
        cancelSleepTimer()
        if (minutes == null || minutes <= 0) return
        sleepAtMs = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.update { it.copy(sleepRemainingMs = minutes * 60_000L, sleepAtEnd = false) }
    }

    /** Таймер сна «в конце серии»: пауза, когда закончится текущий файл (без автоперехода к следующему). */
    fun setSleepAtEndOfItem() {
        cancelSleepTimer()
        player.pauseAtEndOfMediaItems = true
        _state.update { it.copy(sleepAtEnd = true, sleepRemainingMs = null) }
    }

    fun cancelSleepTimer() {
        sleepAtMs = 0
        sleepFadeJob?.cancel()
        sleepFadeJob = null
        player.volume = 1f
        player.pauseAtEndOfMediaItems = true
        _state.update { it.copy(sleepRemainingMs = null, sleepAtEnd = false) }
    }

    /** Раз в полсекунды: обновить остаток таймера; время вышло - 8 секунд затихания и пауза. */
    private fun tickSleepTimer() {
        if (sleepAtMs == 0L || sleepFadeJob != null) return
        val left = sleepAtMs - SystemClock.elapsedRealtime()
        if (left > 0) {
            _state.update { it.copy(sleepRemainingMs = left) }
            return
        }
        sleepFadeJob = scope.launch {
            for (step in 15 downTo 0) {
                player.volume = step / 16f
                delay(500)
            }
            player.pause()
            saveProgress()
            cancelSleepTimer()
        }
    }

    fun setSubtitleDelay(ms: Long) {
        _state.update { it.copy(subtitleDelayMs = ms) }
        applyTextClock()
    }

    /** Подключает внешний аудиофайл к текущему видео и сразу включает его. Позиция не сбрасывается. */
    fun addExternalAudio(uri: Uri, name: String? = null) {
        val index = player.currentMediaItemIndex
        if (index !in audios.indices) return
        SubtitleSupport.persistReadPermission(appContext, uri)
        val displayName = name ?: SubtitleSupport.displayName(appContext, uri)
        val audio = ExternalAudio(
            uri = uri,
            name = displayName,
            language = SubtitleSupport.languageFromName(displayName),
            select = true,
        )
        val list = audios[index]
        val existing = list.indexOfFirst { it.uri == uri }
        if (existing >= 0) list[existing] = audio else list.add(audio)
        val hint = CarryHint(externalId = audio.stableId(), name = audio.name, language = audio.language)
        sessionAudio = hint
        pendingAudioHint = hint
        keyAt(index)?.let { store.upsertExternalAudio(it, audio) }
        scope.launch {
            val position = player.currentPosition
            val playing = player.playWhenReady
            player.replaceMediaItem(index, buildMediaItem(index))
            player.seekTo(index, position)
            player.playWhenReady = playing
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
    }

    /** Подключает внешний файл субтитров к текущему видео и сразу включает его. */
    fun addExternalSubtitle(uri: Uri, name: String? = null) {
        val index = player.currentMediaItemIndex
        if (index < 0 || index >= subtitles.size) return
        SubtitleSupport.persistReadPermission(appContext, uri)
        val displayName = name ?: SubtitleSupport.displayName(appContext, uri)
        val sub = ExternalSubtitle(uri, displayName, SubtitleSupport.languageFromName(displayName), select = true)
        val list = subtitles[index]
        val existing = list.indexOfFirst { it.uri == uri }
        if (existing >= 0) list[existing] = sub else list.add(sub)
        markExternalSelected(index, uri)
        pinExternalSubtitle(index, sub)
        Log.i(PLAYER_LOG_TAG, "external subtitle name=$displayName uri=$uri")
        scope.launch {
            val position = player.currentPosition
            val playing = player.playWhenReady
            pendingExternalSelect = externalId(index, sub)
            player.replaceMediaItem(index, buildMediaItem(index))
            pendingExternalSelect = externalId(index, sub)
            player.seekTo(index, position)
            player.playWhenReady = playing
        }
    }

    // region Главы и отрезки

    fun nextChapter() {
        val pos = player.currentPosition
        chapters.firstOrNull { it.startMs > pos + 1_000 }?.let { seekTo(it.startMs) }
    }

    /** Как на CD-плеере: в начале главы - к предыдущей, иначе - к началу текущей. */
    fun previousChapter() {
        val pos = player.currentPosition
        val current = chapters.indexOfLast { it.startMs <= pos }
        if (current < 0) return
        val target = if (pos - chapters[current].startMs > 3_000 || current == 0) chapters[current] else chapters[current - 1]
        seekTo(target.startMs)
    }

    fun seekToChapter(chapter: Chapter) = seekTo(chapter.startMs)

    fun skip(segment: SkipSegment) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        autoSkipped += segment
        if (segment.type == SegmentType.CREDITS && (segment.endMs == null || segment.endMs >= duration - 1_000) && player.hasNextMediaItem()) {
            next()
        } else {
            seekTo(segment.endMs ?: duration)
        }
    }

    fun markIntroStart() = updateMarks { it.copy(introStartMs = player.currentPosition) }

    fun markIntroEnd() = updateMarks {
        val pos = player.currentPosition
        it.copy(introEndMs = pos, introStartMs = it.introStartMs?.takeIf { s -> s < pos })
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

    /** Кадр для превью при перемотке; null - источник не позволяет. */
    suspend fun frameAt(positionMs: Long): Bitmap? {
        // Кадр из бесконечного эфира FFmpeg не достанет - только зря нагрузит сеть.
        if (request.liveTv) return null
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

    /** Сводка «что внутри и как играет» для текущего файла. */
    fun report(): MediaReport {
        val entry = request.items.getOrNull(player.currentMediaItemIndex)
        val container = when {
            discs[player.currentMediaItemIndex] != null -> "Blu-ray"
            else -> entry?.uri?.lastPathSegment?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.length in 2..5 }
        }
        return MediaReporter.build(appContext, _state.value.title, container, player.duration.coerceAtLeast(0), player.currentTracks, decoders, i18n.current)
    }

    fun dismissWarnings() = _state.update { it.copy(warnings = emptyList()) }

    fun retry() {
        introItem = -1
        introAttempts = 0
        stallRecoveries = 0
        stallStopped = false
        holdFailedItem = -1
        resetWatchdogClock()
        _state.update { it.copy(error = null) }
        player.prepare()
        player.play()
    }

    fun dismissResumeHint() = _state.update { it.copy(resumedFromMs = null) }

    fun continueFromPrompt() {
        _state.update { it.copy(resumePromptMs = null, resumedFromMs = it.resumePromptMs) }
        player.play()
    }

    fun startOverFromPrompt() {
        _state.update { it.copy(resumePromptMs = null, resumedFromMs = null) }
        player.seekTo(0)
        player.play()
    }

    fun cancelEndCountdown() {
        endHoldSince = SystemClock.elapsedRealtime()
        _state.update { it.copy(endCountdownSec = null, endDim = false) }
    }

    fun advanceAfterEnd() {
        _state.update { it.copy(endCountdownSec = null, showEndScreen = false) }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun watchAgain() {
        naturalEndHandled = false
        completed = false
        endHoldSince = 0L
        _state.update { it.copy(showEndScreen = false, endCountdownSec = null, endDim = false) }
        player.seekTo(0)
        player.play()
    }

    fun requestClose() = _state.update { it.copy(requestClose = true) }

    fun acknowledgeClose() = _state.update { it.copy(requestClose = false) }

    fun dismissEndDim() {
        val now = SystemClock.elapsedRealtime()
        if (endHoldSince != 0L) endHoldSince = now
        pausedSince = now
        _state.update { it.copy(endDim = false) }
    }

    private var naturalEndHandled = false
    private var endHoldSince = 0L
    /** Пункт, на котором показана ошибка. Автопереход к следующему отменяется. */
    private var holdFailedItem = -1

    private fun onNaturalEnd() {
        if (naturalEndHandled) return
        naturalEndHandled = true
        if (_state.value.sleepAtEnd) {
            cancelSleepTimer()
            return
        }
        val live = isLivePlayback()
        if (!live) {
            completed = true
            saveProgress()
        }
        val plan = endPlan(settings.current.endAction, player.hasNextMediaItem(), request.items.size, live)
        when {
            plan.repeat -> {
                naturalEndHandled = false
                completed = false
                player.seekTo(0)
                player.play()
            }
            plan.closeNow -> requestClose()
            plan.countdown -> _state.update { it.copy(endCountdownSec = END_COUNTDOWN_SEC, showEndScreen = false, playlistFinished = false, endDim = false) }
            plan.playlistDone -> _state.update { it.copy(playlistFinished = true, endCountdownSec = null, showEndScreen = false, endDim = false) }
            plan.endScreen -> _state.update { it.copy(showEndScreen = true, endCountdownSec = null, playlistFinished = false, endDim = false) }
            plan.stay -> {
                endHoldSince = if (live) 0L else SystemClock.elapsedRealtime()
                _state.update { it.copy(endCountdownSec = null, showEndScreen = false, playlistFinished = false, endDim = false) }
            }
        }
    }

    private fun isLivePlayback(): Boolean {
        if (request.liveTv || player.isCurrentMediaItemLive || _state.value.isLive) return true
        val uri = request.items.getOrNull(player.currentMediaItemIndex)?.uri
        return uri != null && isLiveSource(uri)
    }

    private var pausedSince = 0L

    private fun tickEndHold() {
        val live = isLivePlayback()
        val ui = _state.value
        val blocked = live || ui.error != null || ui.showEndScreen || ui.endCountdownSec != null ||
            ui.playlistFinished || ui.resumePromptMs != null
        if (player.isPlaying || blocked) {
            if (player.isPlaying) {
                endHoldSince = 0L
                pausedSince = 0L
            }
            if (ui.endDim && (player.isPlaying || blocked)) _state.update { it.copy(endDim = false) }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (endHoldSince == 0L) {
            if (!settings.current.oledSaver) {
                pausedSince = 0L
                if (ui.endDim) _state.update { it.copy(endDim = false) }
                return
            }
            if (pausedSince == 0L) pausedSince = now
            if (endShouldDim(true, false, now - pausedSince) && !ui.endDim) {
                _state.update { it.copy(endDim = true) }
            }
            return
        }
        if (endShouldDim(true, false, now - endHoldSince) && !ui.endDim) {
            _state.update { it.copy(endDim = true) }
        }
    }

    /** Обложка следующего пункта: уже сохранённая, переданная или картинка рядом с локальным файлом. */
    private fun previewSource(index: Int): String? {
        val item = request.items.getOrNull(index) ?: return null
        keyAt(index)?.let { key -> Covers.get(appContext, key)?.absolutePath }?.let { return it }
        val art = item.artworkUri
        if (art != null) {
            when (art.scheme?.lowercase()) {
                "file" -> art.path?.takeIf { java.io.File(it).isFile }?.let { return it }
                "http", "https", "content" -> return art.toString()
            }
        }
        if (item.uri.scheme?.lowercase() == "file") {
            return Covers.sidecarCandidates(item.uri).firstNotNullOfOrNull { candidate ->
                candidate.path?.takeIf { java.io.File(it).isFile }
            }
        }
        return null
    }

    /** Начать текущий файл с начала (кнопка в подсказке «Продолжено с …»). */
    fun restartFromBeginning() {
        player.seekTo(0)
        dismissResumeHint()
    }

    // endregion

    fun result(): PlaybackResult {
        val duration = player.duration.coerceAtLeast(0)
        val position = if (completed) duration else player.currentPosition.coerceAtLeast(0)
        val watched = completed || ResumeStore.isFinished(position, duration)
        val selected = selectedTracks()
        return PlaybackResult(
            uri = request.items.getOrNull(player.currentMediaItemIndex)?.uri,
            positionMs = if (watched) duration else position,
            durationMs = duration,
            completed = watched,
            audio = selected.first,
            text = selected.second,
            textOff = selected.third,
        )
    }

    fun saveProgress() {
        val duration = player.duration
        // Длительность неизвестна (поток ещё не открылся) - иначе в истории окажется «0:00».
        if (duration == C.TIME_UNSET || duration <= 0) return
        val position = if (completed) duration else player.currentPosition
        saveFor(player.currentMediaItemIndex, position, duration)
    }

    private fun saveFor(index: Int, positionMs: Long, durationMs: Long) {
        if (!config.saveHistory || request.liveTv) return
        val key = keyAt(index) ?: return
        val entry = request.items.getOrNull(index) ?: return
        // Автопереход уже переключил плеер: дорожки и «это прямой эфир» относятся к текущему пункту,
        // а не к сохраняемому. Живой текущий пункт историю не пишет; предыдущий фильм - пишет.
        val current = index == player.currentMediaItemIndex
        if (current && player.isCurrentMediaItemLive) return
        val previous = if (current) null else store.get(key)
        val tracks = if (current) player.currentTracks else null
        val audio = tracks?.let { TrackLabels.collect(it, C.TRACK_TYPE_AUDIO, i18n.current).firstOrNull { t -> t.selected } }
        val text = tracks?.let { TrackLabels.collect(it, C.TRACK_TYPE_TEXT, i18n.current).firstOrNull { t -> t.selected } }
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
                audioLanguage = if (current) audio?.language else previous?.audioLanguage,
                audioLabel = if (current) audio?.label else previous?.audioLabel,
                textLanguage = if (current) text?.language else previous?.textLanguage,
                textLabel = if (current) text?.label?.takeUnless { text.external } else previous?.textLabel,
                textDisabled = if (current) {
                    player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                } else {
                    previous?.textDisabled ?: false
                },
                speed = if (current) playbackSpeed else previous?.speed ?: 1f,
                subtitleDelayMs = if (current) _state.value.subtitleDelayMs else previous?.subtitleDelayMs ?: 0,
            )
        )
    }

    fun release() {
        PlaybackSessions.detach(appContext, this)
        saveProgress()
        publishNowPlaying()
        PlayerExtensions.publish(PlayerExtensions.nowPlaying.value?.copy(isPlaying = false))
        progressJob?.cancel()
        analyzeJob?.cancel()
        introJob?.cancel()
        coverJob?.cancel()
        statsCollector.stop()
        appContext.getSystemService(android.hardware.display.DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        scope.cancel()
        analyzer.release()
        discs.values.forEach(DiscSessions::close)
        discs.clear()
        player.removeListener(listener)
        built.release()
    }

    /**
     * Живой источник: держим задержку около [LIVE_TARGET_MS]. Буфер вырос (сеть «плюнула» пачкой, плеер отстал) -
     * ускоряемся (×1,1, сильно отстали - ×1,25; тон звука сохраняется), догнали - обычная скорость. Перемоткой
     * не прыгаем: у RTSP она переоткрывает поток, а камерам-серверам переходы по времени обычно не нужны.
     */
    private fun keepLiveEdge() {
        // Догон только на обычной скорости: иначе он затирает выбор пользователя и 2× от удержания,
        // а сохранённая скорость потом открывала тот же поток уже ускоренным.
        if (!lowLatency || !player.isPlaying || speedBeforeBoost != null || playbackSpeed != 1f) {
            if (liveCatchup && speedBeforeBoost == null) {
                player.setPlaybackSpeed(playbackSpeed)
                liveCatchup = false
            }
            return
        }
        val buffered = player.totalBufferedDuration
        when {
            buffered > LIVE_FAR_MS -> {
                if (player.playbackParameters.speed != LIVE_FAR_SPEED) player.setPlaybackSpeed(LIVE_FAR_SPEED)
                liveCatchup = true
            }
            buffered in (LIVE_CATCHUP_MS + 1)..LIVE_FAR_MS -> {
                if (player.playbackParameters.speed != LIVE_CATCHUP_SPEED) player.setPlaybackSpeed(LIVE_CATCHUP_SPEED)
                liveCatchup = true
            }
            buffered <= LIVE_TARGET_MS && liveCatchup -> {
                player.setPlaybackSpeed(playbackSpeed)
                liveCatchup = false
            }
        }
    }

    private fun startProgressLoop() {
        progressJob = scope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                refresh(includeTracks = false)
                maybeAutoSkip()
                keepLiveEdge()
                tickSleepTimer()
                tickEndHold()
                if (++ticks % 10 == 0 && player.isPlaying) saveProgress()
                if (ticks % 2 == 0) publishNowPlaying()
                if (ticks % 120 == 0) applyNightSchedule()
                maybeRecoverStall()
                // Подпись обновляем раз в 30 с; пока телегид грузится - чаще.
                if (request.liveTv && ticks % (if (_state.value.subtitle == null) 10 else 60) == 0) {
                    val subtitle = liveSubtitle(player.currentMediaItemIndex)
                    if (subtitle != _state.value.subtitle) _state.update { it.copy(subtitle = subtitle) }
                }
                // Через пару секунд после старта, когда декодеры выбраны, - показываем важные предупреждения.
                if (warningsShownFor != player.currentMediaItemIndex && player.isPlaying && player.currentPosition > 2_000) {
                    warningsShownFor = player.currentMediaItemIndex
                    val w = runCatching { report().warnings }.getOrDefault(emptyList())
                    if (w.isNotEmpty()) _state.update { it.copy(warnings = w) }
                }
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
        if (segment.type == SegmentType.CREDITS || segment in autoSkipped || segment in lowConfidence) return
        skip(segment)
    }

    /**
     * @param includeTracks собирать списки дорожек. Тик прогресса (два раза в секунду) этого не делает:
     * списки не меняются, а разбор дорожек и новая композиция боковой панели давали пропуски кадров,
     * пока меню плеера было открыто. Смена дорожек приходит отдельным событием и вызывает полный refresh.
     */
    private fun refresh(includeTracks: Boolean = true) {
        val p = player
        val tracks = p.currentTracks
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val video = p.videoSize
        val rawPosition = p.currentPosition.coerceAtLeast(0)
        val position = if (duration > 0 && rawPosition > duration) duration else rawPosition
        if (duration > 0 && rawPosition >= duration) finishAtVideoEnd()
        val builtSegments = if (duration > 0) currentSegments(duration) else emptyList()
        val skipMode = settings.current.skipMode
        _state.update { prev ->
            val segments = if (prev.segments == builtSegments) prev.segments else builtSegments
            val audio = if (!includeTracks) prev.audioTracks else TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current).let { if (it == prev.audioTracks) prev.audioTracks else it }
            val text = if (!includeTracks) prev.textTracks else TrackLabels.collect(tracks, C.TRACK_TYPE_TEXT, i18n.current).let { if (it == prev.textTracks) prev.textTracks else it }
            val videoTracks = if (!includeTracks) prev.videoTracks else TrackLabels.collect(tracks, C.TRACK_TYPE_VIDEO, i18n.current).let { if (it == prev.videoTracks) prev.videoTracks else it }
            val next = prev.copy(
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                ended = p.playbackState == Player.STATE_ENDED,
                positionMs = position,
                durationMs = duration,
                bufferedMs = p.bufferedPosition,
                speed = p.playbackParameters.speed,
                audioTracks = audio,
                textTracks = text,
                videoTracks = videoTracks,
                textDisabled = if (includeTracks) p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) else prev.textDisabled,
                subtitleDelayMs = prev.subtitleDelayMs,
                hasVideo = if (includeTracks) tracks.isEmpty || tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, true) else prev.hasVideo,
                videoAspect = if (includeTracks) {
                    if (video.height > 0) video.width * video.pixelWidthHeightRatio / video.height else 0f
                } else prev.videoAspect,
                hasNext = if (request.liveTv) p.mediaItemCount > 1 else p.hasNextMediaItem(),
                hasPrevious = if (request.liveTv) p.mediaItemCount > 1 else p.hasPreviousMediaItem(),
                isLive = transportShowsLiveBadge(
                    requestedLive = request.liveTv,
                    playerSaysLive = transportIsLive(
                        isCurrentMediaItemLive = p.isCurrentMediaItemLive,
                        isCurrentMediaItemDynamic = p.isCurrentMediaItemDynamic,
                        hasLiveConfiguration = p.currentMediaItem?.liveConfiguration != null,
                    ),
                    durationMs = duration,
                    playbackReady = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED,
                ),
                playlistIndex = p.currentMediaItemIndex,
                title = if (includeTracks) titleAt(p.currentMediaItemIndex) else prev.title,
                chapters = chapters,
                chapterIndex = chapters.indexOfLast { ch -> ch.startMs <= position },
                segments = segments,
                activeSegment = if (skipMode == SkipMode.OFF) null else segments.firstOrNull { s -> s.contains(position, duration) },
                videoFrameRate = if (includeTracks) p.videoFormat?.frameRate?.takeIf { f -> f > 0f } ?: detectedFrameRate else prev.videoFrameRate,
            )
            if (next == prev) prev else next
        }
    }

    private suspend fun buildMediaItem(index: Int): MediaItem {
        val entry: MediaEntry = request.items[index]
        val external = subtitles[index]
        val remembered = keyAt(index)?.let(store::externalSubtitles).orEmpty()
        val includeSidecars = !sidecarsScanned[index] && entry.subtitles.isEmpty()
        val sidecars = if (includeSidecars) {
            sidecarsScanned[index] = true
            discoverSidecars(entry.uri)
        } else emptyList()
        val merged = SubtitleSupport.mergeExternal(external, remembered, sidecars, includeSidecars)
        if (merged != external) {
            external.clear()
            external.addAll(merged)
        }
        val configs = external.map { sub ->
            val name = sub.name ?: SubtitleSupport.displayName(appContext, sub.uri)
            val normalized = SubtitleSupport.normalize(appContext, sub.uri, request.headers, name, sub.charset)
            val mime = sub.mimeType?.takeIf { it.isNotBlank() }
                ?: SubtitleSupport.mimeForName(name)
                ?: SubtitleSupport.mimeForName(normalized.lastPathSegment)
                ?: MimeTypes.APPLICATION_SUBRIP
            val builder = MediaItem.SubtitleConfiguration.Builder(normalized)
                .setId(externalId(index, sub))
                .setMimeType(mime)
                .setLanguage(sub.language ?: SubtitleSupport.languageFromName(name))
                .setLabel(name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() })
            if (sub.select) builder.setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            builder.build()
        }
        // ISO-образ или папка BDMV: открываем диск и играем основной фильм.
        val disc = discs[index] ?: if (DiscSessions.isCandidate(entry.uri)) {
            withContext(Dispatchers.IO) { runCatching { DiscSessions.open(appContext, entry.uri, request.headers) }.getOrNull() }
                ?.also { discs[index] = it }
        } else null
        disc?.disc?.discTitle?.let { if (entry.title.isNullOrBlank()) titles[index] = it }
        if (!request.liveTv) {
            val scheme = entry.uri.scheme?.lowercase()
            val http = scheme == "http" || scheme == "https"
            val remembered = keyAt(index)?.let(store::externalAudio).orEmpty()
            val discovered = if (http && audioSidecarsScanned[index]) emptyList() else {
                if (http) audioSidecarsScanned[index] = true
                discoverAudio(entry.uri)
            }
            for (audio in remembered + discovered) considerAudio(index, audio)
        }
        val playable = mutableListOf<ExternalAudio>()
        val failed = mutableListOf<String>()
        for (audio in audios[index]) {
            val ok = withContext(Dispatchers.IO) {
                AudioSupport.reachable(appContext, audio.uri, request.headers, if (audio.uri.scheme.equals("http", true) || audio.uri.scheme.equals("https", true)) 8000 else 4000)
            }
            if (ok) playable += audio else failed += (audio.name ?: audio.uri.lastPathSegment ?: audio.uri.toString())
        }
        if (failed.isNotEmpty()) {
            val message = i18n.current.format("error.external_audio", failed.joinToString())
            _state.update { it.copy(warnings = (it.warnings + message).distinct()) }
        }
        audios[index].clear()
        audios[index].addAll(playable)
        val title = withContext(Dispatchers.IO) { titleFor(entry) }
        val builder = MediaItem.Builder()
            .setUri(disc?.titleUri ?: entry.uri)
            .setMediaId(keyAt(index) ?: entry.uri.toString())
            .setMimeType(adaptiveMime(entry))
            .setSubtitleConfigurations(configs)
            .setTag(ExternalAudioTag(playable.mapIndexed { i, audio -> audio.toSpec(i) }))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (request.liveTv) builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        return builder.build()
    }

    /**
     * Субтитры рядом с файлом. Для `content://` без читаемого каталога один раз спрашивает папку
     * ([SubtitleFolders]): повторный запуск того же ролика диалог не открывает.
     */
    private suspend fun discoverSidecars(uri: Uri): List<ExternalSubtitle> {
        if (uri.scheme.equals("content", true)) {
            val search = withContext(Dispatchers.IO) { ContentSidecars.search(appContext, uri) }
            val hits = if (!search.askForFolder) search.hits else {
                val tree = SubtitleFolders.request(appContext, uri)
                if (tree == null) emptyList() else withContext(Dispatchers.IO) { ContentSidecars.listTree(appContext, uri, tree) }
            }
            return hits.map { ExternalSubtitle(it.uri, it.name, SubtitleSupport.languageFromName(it.name)) }
        }
        return withContext(Dispatchers.IO) { SubtitleSupport.findSidecars(appContext, uri) }.map { found ->
            val name = found.lastPathSegment?.substringAfterLast('/')
            ExternalSubtitle(found, name, SubtitleSupport.languageFromName(name))
        }
    }

    /** Озвучка рядом с файлом. `content://` идёт тем же путём, что субтитры, плюс папка озвучки на уровень ниже. */
    private suspend fun discoverAudio(uri: Uri): List<ExternalAudio> {
        if (uri.scheme.equals("content", true)) {
            val extensions = AudioSupport.EXTENSIONS.toSet()
            val search = withContext(Dispatchers.IO) {
                ContentSidecars.search(appContext, uri, extensions, AudioSupport::isAudioFolder)
            }
            val hits = if (!search.askForFolder) search.hits else {
                val tree = SubtitleFolders.request(appContext, uri)
                if (tree == null) emptyList() else withContext(Dispatchers.IO) {
                    ContentSidecars.listTree(appContext, uri, tree, extensions, AudioSupport::isAudioFolder)
                }
            }
            return AudioSupport.fromHits(hits)
        }
        return withContext(Dispatchers.IO) { AudioSupport.findSidecars(appContext, uri, request.headers) }
    }

    private fun explicitAudioHint(index: Int): CarryHint? {
        val item = request.items.getOrNull(index) ?: return null
        val list = audios.getOrNull(index).orEmpty()
        TrackSelect.parse(item.audioSelect)?.let { return hintFromSelect(it, list.map { ref -> ref.stableId() to (ref.name to ref.language) }) }
        return list.firstOrNull { it.select }?.let { CarryHint(externalId = it.stableId(), name = it.name, language = it.language) }
    }

    private fun explicitTextHint(index: Int): CarryHint? {
        val item = request.items.getOrNull(index) ?: return null
        val list = subtitles.getOrNull(index).orEmpty()
        TrackSelect.parse(item.textSelect)?.let { return hintFromSelect(it, list.map { ref -> ref.stableId() to (ref.name to ref.language) }) }
        return null
    }

    private fun hintFromSelect(parsed: TrackSelect.Parsed, tracks: List<Pair<String, Pair<String?, String?>>>): CarryHint {
        if (parsed.off) return CarryHint(off = true)
        if (parsed.embeddedIndex != null && parsed.externalId == null && parsed.externalIndex == null) {
            return CarryHint(embeddedIndex = parsed.embeddedIndex)
        }
        val ref = when {
            parsed.externalId != null -> tracks.firstOrNull { it.first == parsed.externalId }
            parsed.externalIndex != null -> tracks.getOrNull(parsed.externalIndex)
            else -> null
        }
        return CarryHint(externalId = ref?.first ?: parsed.externalId, name = ref?.second?.first, language = ref?.second?.second)
    }

    private fun audioCarry(options: List<TrackOption>, index: Int): List<CarryTrack> {
        val list = audios.getOrNull(index).orEmpty()
        var embedded = 0
        return options.map { option ->
            if (!option.external) {
                CarryTrack(name = option.rawLabel, language = option.language, embeddedIndex = embedded++)
            } else {
                val audio = externalAudioIndex(option.formatId)?.let { list.getOrNull(it) }
                CarryTrack(
                    externalId = audio?.stableId(),
                    name = audio?.name ?: option.rawLabel,
                    language = audio?.language ?: option.language,
                    external = true,
                )
            }
        }
    }

    private fun textCarry(options: List<TrackOption>, index: Int): List<CarryTrack> {
        val list = subtitles.getOrNull(index).orEmpty()
        var embedded = 0
        return options.map { option ->
            if (!option.external) {
                CarryTrack(name = option.rawLabel, language = option.language, embeddedIndex = embedded++)
            } else {
                val sub = list.firstOrNull { candidate ->
                    val id = externalId(index, candidate)
                    option.formatId == id || option.formatId?.endsWith(id) == true
                }
                CarryTrack(
                    externalId = sub?.stableId(),
                    name = sub?.name ?: option.rawLabel,
                    language = sub?.language ?: option.language,
                    external = true,
                )
            }
        }
    }

    private fun hintForAudio(option: TrackOption): CarryHint {
        if (!option.external) {
            val embedded = TrackLabels.collect(player.currentTracks, C.TRACK_TYPE_AUDIO, i18n.current).filter { !it.external }
            val at = embedded.indexOfFirst { it.groupIndex == option.groupIndex && it.trackIndex == option.trackIndex }
            return CarryHint(name = option.rawLabel, language = option.language, embeddedIndex = at.takeIf { it >= 0 })
        }
        val audio = audioFor(option)
        return CarryHint(externalId = audio?.stableId(), name = audio?.name ?: option.rawLabel, language = audio?.language ?: option.language)
    }

    private fun hintForText(option: TrackOption): CarryHint {
        if (!option.external) {
            val embedded = TrackLabels.collect(player.currentTracks, C.TRACK_TYPE_TEXT, i18n.current).filter { !it.external }
            val at = embedded.indexOfFirst { it.groupIndex == option.groupIndex && it.trackIndex == option.trackIndex }
            return CarryHint(name = option.rawLabel, language = option.language, embeddedIndex = at.takeIf { it >= 0 })
        }
        val index = player.currentMediaItemIndex
        val sub = subtitles.getOrNull(index)?.firstOrNull { candidate ->
            val id = externalId(index, candidate)
            option.formatId == id || option.formatId?.endsWith(id) == true
        }
        return CarryHint(externalId = sub?.stableId(), name = sub?.name ?: option.rawLabel, language = sub?.language ?: option.language)
    }

    private fun audioFor(option: TrackOption): ExternalAudio? {
        val at = externalAudioIndex(option.formatId) ?: return null
        return audios.getOrNull(player.currentMediaItemIndex)?.getOrNull(at)
    }

    /** Какая внешняя дорожка сейчас выбрана: по списку плеера, иначе по флагу select. */
    private fun selectedExternalIndex(index: Int): Int? {
        val list = audios.getOrNull(index) ?: return null
        val fromPlayer = TrackLabels.collect(player.currentTracks, C.TRACK_TYPE_AUDIO, i18n.current)
            .firstOrNull { it.selected && it.external }
            ?.let { externalAudioIndex(it.formatId) }
        if (fromPlayer != null && fromPlayer in list.indices) return fromPlayer
        return list.indexOfFirst { it.select }.takeIf { it >= 0 }
    }

    private fun markAudioSelected(index: Int, uri: Uri?) {
        val list = audios.getOrNull(index) ?: return
        for (i in list.indices) {
            val want = uri != null && list[i].uri == uri
            if (list[i].select != want) list[i] = list[i].copy(select = want)
        }
    }

    /** Снять запомненный выбор только этого файла. Сама строка в панели остаётся. */
    private fun clearRememberedExternal(option: TrackOption) {
        val index = player.currentMediaItemIndex
        val audio = audioFor(option) ?: return
        val list = audios.getOrNull(index) ?: return
        val at = list.indexOfFirst { it.uri == audio.uri }
        if (at >= 0 && list[at].select) list[at] = list[at].copy(select = false)
        val key = keyAt(index) ?: return
        val saved = store.externalAudio(key).firstOrNull { it.select }?.uri
        if (saved == audio.uri) store.setExternalAudioSelected(key, null)
    }

    private fun warnExternalCodec(label: String) {
        val message = i18n.current.format("error.external_audio_codec", label)
        _state.update { state -> state.copy(warnings = state.warnings.filterNot { it == message } + message) }
    }

    private fun audioOffset(option: TrackOption): Long {
        if (!option.external) return 0
        return audioFor(option)?.delayMs ?: 0
    }

    private fun textOffset(option: TrackOption): Long {
        if (!option.external) return 0
        val index = player.currentMediaItemIndex
        return subtitles.getOrNull(index)?.firstOrNull { candidate ->
            val id = externalId(index, candidate)
            option.formatId == id || option.formatId?.endsWith(id) == true
        }?.delayMs ?: 0
    }

    private fun applyAudioClock() {
        built.passthrough.audioDelayUs = (settings.current.audioDelayMs + appliedAudioOffsetMs) * 1000L
    }

    private fun applyTextClock() {
        player.subtitleDelayMilliseconds = _state.value.subtitleDelayMs + appliedTextOffsetMs
    }

    private fun selectedTracks(): Triple<SelectedTrack?, SelectedTrack?, Boolean> {
        val index = player.currentMediaItemIndex
        val textOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        return Triple(describeSelected(C.TRACK_TYPE_AUDIO, index), if (textOff) null else describeSelected(C.TRACK_TYPE_TEXT, index), textOff)
    }

    private fun describeSelected(type: Int, index: Int): SelectedTrack? {
        val options = TrackLabels.collect(player.currentTracks, type, i18n.current)
        val selected = options.firstOrNull { it.selected } ?: return null
        val embeddedIndex = if (selected.external) -1 else options.filter { !it.external }.indexOf(selected)
        val externalIndex = if (!selected.external) -1 else options.filter { it.external }.indexOf(selected)
        if (selected.external && type == C.TRACK_TYPE_AUDIO) {
            val audio = audioFor(selected)
            return SelectedTrack(
                id = audio?.stableId() ?: "external:$externalIndex",
                name = audio?.name ?: selected.rawLabel,
                language = audio?.language ?: selected.language,
                external = true,
                externalIndex = externalIndex,
            )
        }
        if (selected.external) {
            val sub = subtitles.getOrNull(index)?.firstOrNull { candidate ->
                val id = externalId(index, candidate)
                selected.formatId == id || selected.formatId?.endsWith(id) == true
            }
            return SelectedTrack(
                id = sub?.stableId() ?: "external:$externalIndex",
                name = sub?.name ?: selected.rawLabel,
                language = sub?.language ?: selected.language,
                external = true,
                externalIndex = externalIndex,
            )
        }
        return SelectedTrack(
            id = "embedded:$embeddedIndex",
            name = selected.rawLabel ?: selected.label,
            language = selected.language,
            external = false,
            embeddedIndex = embeddedIndex,
        )
    }

    private fun ExternalAudio.toSpec(index: Int) = AudioSpec(
        uri = uri,
        listIndex = index,
        name = name ?: uri.lastPathSegment?.substringBeforeLast('.'),
        language = language,
        mime = mimeType ?: AudioSupport.mimeForName(name ?: uri.lastPathSegment),
        delayMs = delayMs,
    )

    /**
     * Ошибка относится к одной внешней дорожке. Остальные остаются.
     * Ошибка самого видео (декодер картинки) сюда не входит.
     * Запись в истории стирается только у этого URI. Сайдкар рядом с файлом найдётся снова,
     * но в этом запуске уже не подмешивается.
     */
    private fun recoverExternalAudio(error: PlaybackException): Boolean {
        val index = player.currentMediaItemIndex
        if (index !in audios.indices || audios[index].isEmpty()) return false
        val exo = error as? ExoPlaybackException
        val mime = exo?.rendererFormat?.sampleMimeType
        if (exo?.type == ExoPlaybackException.TYPE_RENDERER && mime != null && MimeTypes.isVideo(mime)) return false
        val at = failingExternalIndex(error, audios[index], selectedExternalIndex(index)) ?: return false
        val codec = exo?.type == ExoPlaybackException.TYPE_RENDERER
        return dropExternalAudioAt(index, at, codec)
    }

    /** Внешний файл, который декодер объявил, но не играет. В списке остаётся, видео идёт со встроенной. */
    private fun noteUnsupportedExternal(tracks: Tracks) {
        val index = player.currentMediaItemIndex
        if (index !in audios.indices || tracks.isEmpty) return
        val audio = TrackLabels.collect(tracks, C.TRACK_TYPE_AUDIO, i18n.current)
        val bad = audio.filter { it.external && !it.supported }
        if (bad.isEmpty()) return
        if (!warnedExternal[index]) {
            warnedExternal[index] = true
            val label = bad.joinToString { it.label }
            val message = i18n.current.format("error.external_audio_codec", label)
            _state.update { it.copy(warnings = (it.warnings + message).distinct()) }
        }
        if (bad.none { it.selected }) return
        bad.filter { it.selected }.forEach { clearRememberedExternal(it) }
        val embedded = audio.firstOrNull { !it.external && it.supported } ?: return
        selectTrack(embedded, user = false)
    }

    /**
     * Кадры не идут, а выбрана внешняя дорожка (типичный Opus на Android 9).
     * Снимаем только её, видео продолжается.
     */
    /**
     * Видео доиграло, а внешний звук ещё кормит часы. Это не «дорожка не поддерживается»:
     * не снимаем её и не запоминаем выбор встроенной.
     */
    private fun pastVideoEnd(): Boolean {
        if (request.liveTv) return false
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return false
        return player.currentPosition >= duration
    }

    /** Конец ролика, даже если звуковой рендерер ещё не отпустил часы. */
    private fun finishAtVideoEnd() {
        if (naturalEndHandled || request.liveTv) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        if (player.currentPosition < duration) return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return
        player.playWhenReady = false
        onNaturalEnd()
    }

    private fun dropStalledExternalAudio(): Boolean {
        if (pastVideoEnd()) return false
        val index = player.currentMediaItemIndex
        if (index !in audios.indices || audios[index].isEmpty()) return false
        val selected = TrackLabels.collect(player.currentTracks, C.TRACK_TYPE_AUDIO, i18n.current)
            .firstOrNull { it.selected && it.external }
        val at = externalAudioIndex(selected?.formatId) ?: return false
        if (at !in audios[index].indices) return false
        resetWatchdogClock()
        return dropExternalAudioAt(index, at, codec = true)
    }

    private fun considerAudio(index: Int, audio: ExternalAudio) {
        if (audio.uri.toString() in rejectedAudio[index]) return
        if (audios[index].none { it.uri == audio.uri }) audios[index] += audio
    }

    private fun dropExternalAudioAt(index: Int, at: Int, codec: Boolean): Boolean {
        if (index !in audios.indices || at !in audios[index].indices) return false
        if (externalAudioRecoveries[index] >= 3) return false
        externalAudioRecoveries[index]++
        val audio = audios[index].removeAt(at)
        rejectedAudio[index] += audio.uri.toString()
        keyAt(index)?.let { store.deleteExternalAudio(it, audio.uri.toString()) }
        val label = audio.name ?: audio.uri.lastPathSegment ?: audio.uri.toString()
        val message = if (codec) i18n.current.format("error.external_audio_codec", label)
        else i18n.current.format("error.external_audio", label)
        val position = player.currentPosition
        val play = player.playWhenReady
        _state.update { it.copy(warnings = (it.warnings + message).distinct(), error = null) }
        scope.launch {
            player.replaceMediaItem(index, buildMediaItem(index))
            player.seekTo(index, position.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = play
        }
        return true
    }

    /**
     * Сбой текущего пункта не должен сам перейти к следующему: ошибка остаётся на этом файле.
     * @return true, переход обработан здесь и [onItemStarted] звать не надо.
     */
    private fun undoFailedAdvance(): Boolean {
        val failed = holdFailedItem
        if (failed < 0 || player.currentMediaItemIndex == failed || _state.value.error == null) return false
        scope.launch(Dispatchers.Main) {
            if (holdFailedItem == failed && player.currentMediaItemIndex != failed && failed < player.mediaItemCount) {
                player.seekTo(failed, 0)
                player.pause()
            }
        }
        return true
    }

    private fun externalId(index: Int, sub: ExternalSubtitle): String =
        "$EXTERNAL_SUB_PREFIX$index:${sub.uri.hashCode()}"

    private fun externalUriFor(option: TrackOption): Uri? {
        val formatId = option.formatId ?: return null
        if (!option.external) return null
        val index = player.currentMediaItemIndex
        return subtitles.getOrNull(index)?.firstOrNull { sub ->
            val id = externalId(index, sub)
            formatId == id || formatId.endsWith(id)
        }?.uri
    }

    private fun markExternalSelected(index: Int, uri: Uri?) {
        val list = subtitles.getOrNull(index) ?: return
        for (i in list.indices) {
            val want = uri != null && list[i].uri == uri
            if (list[i].select != want) list[i] = list[i].copy(select = want)
        }
    }

    private fun pinExternalSubtitle(index: Int, sub: ExternalSubtitle) {
        val key = keyAt(index) ?: return
        store.upsertExternalSubtitle(key, sub)
        enableRememberedText(key, sub.language)
    }

    /** Сохранённое «субтитры выключены» не должно погасить только что выбранный внешний файл. */
    private fun enableRememberedText(key: String, language: String?) {
        val entry = store.get(key) ?: return
        if (!entry.textDisabled && entry.textLabel == null && (language == null || entry.textLanguage == language)) return
        store.save(entry.copy(textDisabled = false, textLanguage = language ?: entry.textLanguage, textLabel = null))
    }

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

    /**
     * Аппаратный декодер отдал первые кадры и упал (типичный XviD/DIVX на Allwinner).
     * В режиме «Авто» один раз пробуем программный MediaCodec этого MIME.
     * FFmpeg - только если [ffmpegCanDecode]. Повтор идёт следующим кадром главного потока:
     * синхронный prepare() внутри onPlayerError бьёт в уже отпущенный MediaCodec
     * (queueInputBuffer в состоянии Released).
     */
    private fun retryVideoDecoder(error: PlaybackException): Boolean {
        if (error.errorCode !in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
            return false
        }
        val exo = error as? ExoPlaybackException ?: return false
        if (exo.type != ExoPlaybackException.TYPE_RENDERER) return false
        val mime = exo.rendererFormat?.sampleMimeType ?: return false
        val next = built.videoGate.advance(
            mime = mime,
            failedDecoder = decoders.video,
            allowFallback = settings.current.decoder == tv.p2160.core.settings.DecoderPreference.AUTO,
            softwareAvailable = softwareVideoDecoderExists(mime),
        ) ?: return false
        val label = codecLabel(mime)
        Log.i(PLAYER_LOG_TAG, "video retry mime=$mime stage=$next was=${decoders.video} audio=${decoders.audio}")
        val warning = if (next == VideoDecoderGate.FFMPEG) {
            i18n.current.format("player.decoder_try_ffmpeg", label)
        } else {
            i18n.current.format("player.decoder_try_sw", label)
        }
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val play = player.playWhenReady
        videoRetryPending = true
        _state.update { it.copy(warnings = it.warnings + warning, error = null) }
        scope.launch(Dispatchers.Main) {
            try {
                player.stop()
                player.prepare()
                if (index >= 0) player.seekTo(index, position) else player.seekTo(position)
                if (play) player.play()
            } finally {
                finishVideoRetry()
            }
        }
        return true
    }

    /**
     * Видео в файле есть, а декодера нет. Иначе селектор дорожек оставляет звук и рисует обложку
     * без ошибки (так было с DIV3 и с FFmpeg-режимом на XviD: libavcodec без mpeg4).
     */
    private fun logAviTracks(tracks: Tracks) {
        if (!currentItemIsAvi()) return
        if (tracks.groups.isEmpty()) return
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                Log.i(
                    PLAYER_LOG_TAG,
                    "AVI player track type=${group.type} mime=${format.sampleMimeType} " +
                        "id=${format.id} selected=${group.isTrackSelected(i)} supported=${group.isTrackSupported(i)}",
                )
            }
        }
    }

    private fun finishVideoRetry() {
        videoRetryPending = false
        if (!unsupportedCheckDeferred) return
        unsupportedCheckDeferred = false
        val tracks = player.currentTracks
        reportMissingAviVideo(tracks)
        reportUnsupportedVideo(tracks)
    }

    /** В контейнере видео объявлено, а экстрактор дорожку не создал. Звук без ошибки больше не оставляем. */
    private fun reportMissingAviVideo(tracks: Tracks) {
        if (videoRetryPending) {
            unsupportedCheckDeferred = true
            return
        }
        if (_state.value.error != null || !currentItemIsAvi()) return
        val declared = AviProbe.video ?: return
        val extracted = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
        if (extracted) return
        val name = declared.compression.ifBlank { declared.handler }.ifBlank { "AVI" }
        Log.i(PLAYER_LOG_TAG, "AVI declares video $name but no video track was exposed")
        val message = i18n.current.format("error.avi_video_missing", name)
        _state.update { it.copy(error = message) }
        scope.launch(Dispatchers.Main) {
            if (_state.value.error == message && player.playbackState != Player.STATE_IDLE) player.stop()
        }
    }

    private fun reportUnsupportedVideo(tracks: Tracks) {
        if (videoRetryPending) {
            unsupportedCheckDeferred = true
            return
        }
        if (_state.value.error != null) return
        val mime = unsupportedVideoMime(
            tracks.groups,
            player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO),
        ) ?: return
        val message = i18n.current.format(videoErrorKey(mime), codecLabel(mime))
        _state.update { it.copy(error = message) }
        scope.launch(Dispatchers.Main) {
            if (_state.value.error == message && player.playbackState != Player.STATE_IDLE) player.stop()
        }
    }

    private fun isTimestampDiscontinuity(error: Exception): Boolean {
        val name = error.javaClass.name
        return "Discontinuity" in name || error.message?.contains("timestamp discontinuity") == true
    }

    /** 8 разрывов за 2 с: так Realtek пишет, когда метки MP3 в AVI не сходятся с кадрами. */
    private fun noteAudioDiscontinuity() {
        if (_state.value.error != null || videoRetryPending || stallStopped) return
        if (!playbackIsDriving()) return
        val now = SystemClock.elapsedRealtime()
        if (now - audioDiscontinuitySince > 2_000) {
            audioDiscontinuitySince = now
            audioDiscontinuityCount = 0
        }
        audioDiscontinuityCount++
        if (audioDiscontinuityCount >= 8 && currentItemIsAvi()) recoverStall()
    }

    /**
     * Нет новых кадров 8 с, и в декодер за это время тоже ничего не пришло.
     * Буферизация, пауза, перемотка и удержание D-pad часы не копят.
     * Если вход декодера не растёт, это экстрактор: звуковой декодер не переключаем.
     */
    private fun maybeRecoverStall() {
        if (stallStopped || _state.value.error != null || videoRetryPending) return
        if (request.liveTv) return
        val now = SystemClock.elapsedRealtime()
        if (!player.isPlaying || player.playbackState != Player.STATE_READY || now < seekQuietUntil) {
            resetWatchdogClock()
            return
        }
        val video = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
        if (!video) return
        videoCounters?.ensureUpdated()
        val frames = renderedFrames()
        val input = videoCounters?.queuedInputBufferCount ?: 0
        if (watchdogArmedAt == 0L) {
            watchdogArmedAt = now
            watchdogBaseFrames = frames
            watchdogBaseInput = input
            return
        }
        if (frames > watchdogBaseFrames) {
            watchdogBaseFrames = frames
            watchdogBaseInput = input
            watchdogArmedAt = now
            return
        }
        if (pastVideoEnd()) {
            finishAtVideoEnd()
            resetWatchdogClock()
            return
        }
        if (now - watchdogArmedAt < FIRST_FRAME_TIMEOUT_MS) return
        val snap = "rendered=$frames input=$input dropped=${videoCounters?.droppedBufferCount ?: -1} " +
            "state=${player.playbackState} pos=${player.currentPosition} audio=${decoders.audio} video=${decoders.video}"
        val inputAdvanced = watchdogBaseInput >= 0 && input > watchdogBaseInput
        Log.i(PLAYER_LOG_TAG, "no new video frames for ${FIRST_FRAME_TIMEOUT_MS}ms $snap inputAdvanced=$inputAdvanced")
        if (!inputAdvanced) {
            Log.i(PLAYER_LOG_TAG, "video input stalled, decoder switch skipped")
            if (dropStalledExternalAudio()) return
            showStallError()
            return
        }
        if (dropStalledExternalAudio()) return
        recoverStall()
    }

    private fun renderedFrames(): Int = videoCounters?.renderedOutputBufferCount ?: 0

    private fun armSeekQuiet() {
        seekQuietUntil = SystemClock.elapsedRealtime() + SEEK_QUIET_MS
        resetWatchdogClock()
    }

    private fun playbackIsDriving(): Boolean =
        player.playWhenReady && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private fun resetWatchdogClock() {
        watchdogArmedAt = 0L
        watchdogBaseFrames = -1
        watchdogBaseInput = -1
        audioDiscontinuityCount = 0
    }

    /** Не больше [MAX_STALL_RECOVERIES] prepare() на файл. Дальше только ошибка. */
    private fun recoverStall() {
        if (stallStopped || videoRetryPending) return
        if (stallRecoveries >= MAX_STALL_RECOVERIES) {
            showStallError()
            return
        }
        stallRecoveries++
        resetWatchdogClock()
        if (!switchAviMp3ToFfmpeg()) showStallError()
    }

    /**
     * AVI с MP3: один раз декодируем звук через FFmpeg (декодер mp3 в libavcodec есть).
     * @return true, если переключение запущено.
     */
    private fun switchAviMp3ToFfmpeg(): Boolean {
        if (ffmpegAudioTried || !currentItemIsAvi()) return false
        val audioMime = player.audioFormat?.sampleMimeType
        if (audioMime != MimeTypes.AUDIO_MPEG || !ffmpegCanDecode(MimeTypes.AUDIO_MPEG)) return false
        ffmpegAudioTried = true
        audioDiscontinuityCount = 0
        Log.i(PLAYER_LOG_TAG, "AVI MP3 switch to FFmpeg audio=${decoders.audio} video=${decoders.video}")
        built.decoderManager.selectAudioDecoder(io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode.FFMPEG)
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        val play = player.playWhenReady
        val warning = i18n.current.format("player.decoder_try_ffmpeg", codecLabel(MimeTypes.AUDIO_MPEG))
        videoRetryPending = true
        _state.update { it.copy(warnings = it.warnings + warning, error = null) }
        scope.launch(Dispatchers.Main) {
            try {
                player.stop()
                player.prepare()
                if (index >= 0) player.seekTo(index, position) else player.seekTo(position)
                if (play) player.play()
            } finally {
                finishVideoRetry()
            }
        }
        return true
    }

    /**
     * Картинка так и не пошла. Ошибка называет кодек, вместо бесконечного индикатора.
     * @return true, ошибка плеера обработана здесь.
     */
    private fun showStallError(): Boolean {
        if (stallStopped) return true
        stallStopped = true
        if (_state.value.error != null) return true
        val mime = player.videoFormat?.sampleMimeType ?: player.audioFormat?.sampleMimeType
        Log.i(PLAYER_LOG_TAG, "playback stalled audio=${decoders.audio} video=${decoders.video} mime=$mime")
        val message = i18n.current.format("error.stalled", codecLabel(mime))
        _state.update { it.copy(error = message) }
        scope.launch(Dispatchers.Main) {
            if (_state.value.error == message && player.playbackState != Player.STATE_IDLE) player.stop()
        }
        return true
    }

    /** Залипший буфер AVI: тот же предел попыток, что и у сторожа кадров. */
    private fun recoverAviStall(): Boolean {
        if (!currentItemIsAvi() || stallStopped) return false
        recoverStall()
        return true
    }

    private fun currentItemIsAvi(): Boolean {
        val uri = request.items.getOrNull(player.currentMediaItemIndex)?.uri ?: return false
        val path = uri.lastPathSegment ?: uri.toString()
        return path.endsWith(".avi", ignoreCase = true)
    }

    /** GMC в MPEG-4 - не «декодера нет», а «этот поток устройство не берёт». */
    private fun videoErrorKey(mime: String): String = decoderErrorKey(
        mime,
        settings.current.decoder,
        built.videoGate.stage(mime),
        gmc = mime == MimeTypes.VIDEO_MP4V && AviProbe.mpeg4Gmc,
    )

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
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> {
                val mime = (e as? ExoPlaybackException)?.rendererFormat?.sampleMimeType
                if (mime == null) t["error.decoder"]
                else t.format(videoErrorKey(mime), codecLabel(mime))
            }
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> t["error.drm"]
            else -> t.format("error.generic", e.errorCodeName)
        }
    }
}
