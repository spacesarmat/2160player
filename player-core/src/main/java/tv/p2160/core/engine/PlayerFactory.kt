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

    fun build(context: Context, settings: Settings, headers: Map<String, String>, config: PlayerConfig = PlayerConfig()): BuiltPlayer {
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
        val loadControl = DefaultLoadControl.Builder()
            .setTargetBufferBytes(config.bufferTargetBytes.takeIf { it > 0 } ?: C.LENGTH_UNSET)
            .setBufferDurationsMs(config.minBufferMs, config.maxBufferMs, config.bufferForPlaybackMs, config.bufferForPlaybackAfterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DiscMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, DolbyVisionFallback.ExtractorsFactoryWrapper(context, m2tsExtractors))))
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
            .build()

        decoderManager.attach(player)
        return BuiltPlayer(player, decoderManager, secondarySubtitles, night, passthrough)
    }
}
