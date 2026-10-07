package tv.p2160.core.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator
import tv.p2160.core.api.DeviceProfile
import tv.p2160.core.api.PlayerConfig
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import tv.p2160.core.bluray.DiscMediaSourceFactory
import tv.p2160.core.m2ts.M2tsExtractorsFactory
import tv.p2160.core.settings.DecoderPreference
import tv.p2160.core.settings.NightSchedule
import tv.p2160.core.source.RoutingDataSource
import tv.p2160.core.settings.Settings

@OptIn(UnstableApi::class)
internal class BuiltPlayer(
    val player: ExoPlayer,
    val decoderManager: DecoderManager,
    val secondarySubtitles: SecondarySubtitles,
    val night: NightAudioProcessor,
    val passthrough: PassthroughGuard,
    /** Память буфера (для статистики: сколько занято). */
    val allocator: DefaultAllocator,
    /** Итоговый предел буфера, байт (0 — без предела). */
    val bufferTargetBytes: Int,
) {
    fun release() {
        decoderManager.detach()
        player.release()
    }
}

/**
 * Сборка ExoPlayer:
 * - аппаратные декодеры + FFmpeg (nextlib) для того, что устройство не умеет:
 *   DTS/DTS-HD, TrueHD, AC3/E-AC3 на боксах без лицензии, FLAC/ALAC/Opus в MKV, MPEG-2/VC-1 видео и т.д.;
 * - passthrough AC3/E-AC3/DTS на ресивер, если он есть (решает штатный AudioSink);
 * - HLS / DASH / SmoothStreaming / RTSP / прогрессивные файлы (MKV, MP4, TS, AVI, WebM, FLV, OGG…).
 */
@OptIn(UnstableApi::class)
internal object PlayerFactory {

    const val USER_AGENT = "2160Player/1.0 (Linux; Android) ExoPlayerLib"

    /** Живые источники, где важна задержка: камеры и эфир по RTSP/RTMP/SRT/UDP. */
    val LOW_LATENCY_SCHEMES = setOf("rtsp", "rtsps", "rtmp", "rtmps", "srt", "udp", "rtp")

    /**
     * @param lowLatency живой источник (камера по RTSP и т.п.): почти без буфера перед стартом и не больше
     *   2 с в буфере — задержка ~0,3–0,5 с вместо 2 с, ценой подгрузок на плохой сети.
     */
    fun build(context: Context, settings: Settings, headers: Map<String, String>, config: PlayerConfig = PlayerConfig(), lowLatency: Boolean = false): BuiltPlayer {
        val mode = when (settings.decoder) {
            DecoderPreference.AUTO -> DecoderMode.AUTO
            DecoderPreference.HARDWARE -> DecoderMode.HARDWARE
            DecoderPreference.FFMPEG -> DecoderMode.FFMPEG
        }
        val decoderManager = DecoderManager(mode, mode)
        val secondarySubtitles = SecondarySubtitles()
        val nightNow = settings.nightModeAt(NightSchedule.nowMinute())
        val night = NightAudioProcessor(downmixToStereo = nightNow).apply { enabled = nightNow }
        val passthrough = PassthroughGuard().apply { disabled = nightNow }
        val renderersFactory = DualTextRenderersFactory(context, secondarySubtitles, night, passthrough)
            .setDecoderManager(decoderManager).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages(*settings.preferredAudioLanguages.toTypedArray())
                    .setPreferredTextLanguages(*settings.preferredSubtitleLanguages.toTypedArray())
                    // Даже если декодер «не уверен» в формате — пробуем, а не молчим.
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    // Туннельный режим (ТВ): кадры идут прямо в дисплей, синхронизацию делает железо.
                    .setTunnelingEnabled(settings.tunneling)
                    // Аудио offload для звука без видео (музыка, видео в фоне); ночной звук требует обработки — тогда нет.
                    .setAudioOffloadPreferences(offloadPreferences(settings.audioOffload && !nightNow))
                    .build()
            )
        }

        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: USER_AGENT
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(config.connectTimeoutMs)
            .setReadTimeoutMs(config.readTimeoutMs)
        // smb:// — свой источник, остальное (file, content, http…) — стандартный.
        val dataSourceFactory = RoutingDataSource.Factory(context, DefaultDataSource.Factory(context, httpFactory))

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )

        // M2TS (Blu-ray: 192-байтные пакеты, TrueHD/LPCM/DTS-HD/PGS) штатный Media3 не читает.
        val m2tsExtractors = M2tsExtractorsFactory(fallback = extractors, hintsForUri = DiscMediaSourceFactory::hintsFor)

        // Предел буфера: без него 4K-ремукс держит ~130 МБ, и слабые ТВ падают от нехватки памяти.
        // По умолчанию — по памяти устройства (24–128 МБ).
        val bufferBytes = when (config.bufferTargetBytes) {
            PlayerConfig.AUTO -> DeviceProfile.bufferTargetBytes(context)
            else -> config.bufferTargetBytes.coerceAtLeast(0)
        }
        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setTargetBufferBytes(bufferBytes.takeIf { it > 0 } ?: C.LENGTH_UNSET)
            .apply {
                if (lowLatency) setBufferDurationsMs(500, 2_000, 100, 300).setPrioritizeTimeOverSizeThresholds(true)
                else setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.bufferForPlaybackMs, config.bufferForPlaybackAfterRebufferMs)
                    .setPrioritizeTimeOverSizeThresholds(false)
            }
            .build()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(RtspOverTcp(DiscMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DolbyVisionFallback.ExtractorsFactoryWrapper(context, m2tsExtractors)))))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(settings.seekStepSeconds * 1000L)
            .setSeekForwardIncrementMs(settings.seekStepSeconds * 1000L)
            // Цикл воспроизведения просыпается, только когда рендерерам есть что делать, — меньше пробуждений процессора.
            .experimentalSetDynamicSchedulingEnabled(true)
            .build()

        decoderManager.attach(player)
        return BuiltPlayer(player, decoderManager, secondarySubtitles, night, passthrough, allocator, bufferBytes)
    }
}

/**
 * RTSP — с RTP внутри TCP-соединения (interleaved). По умолчанию Media3 сначала ждёт RTP по UDP, а по Wi-Fi
 * (телефон → ТВ, точки доступа, NAT) UDP часто теряется или режется: картинка не приходит. TCP надёжнее,
 * задержка почти та же. Остальные источники — как у [delegate].
 */
@OptIn(UnstableApi::class)
internal class RtspOverTcp(private val delegate: androidx.media3.exoplayer.source.MediaSource.Factory) :
    androidx.media3.exoplayer.source.MediaSource.Factory by delegate {
    private val rtsp = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory().setForceUseRtpTcp(true)

    override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): androidx.media3.exoplayer.source.MediaSource =
        if (mediaItem.localConfiguration?.uri?.scheme.equals("rtsp", ignoreCase = true)) rtsp.createMediaSource(mediaItem)
        else delegate.createMediaSource(mediaItem)
}
