package tv.p2160.core.engine

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Вторые субтитры (например, английские сверху и русские снизу).
 *
 * ExoPlayer выводит одну текстовую дорожку на рендерер, поэтому добавляем второй TextRenderer.
 * Он стоит перед основным и «умеет» только выбранную вторую дорожку: так при распределении дорожек
 * по рендерерам она достаётся ему, а все остальные - основному.
 */
@OptIn(UnstableApi::class)
class SecondarySubtitles {
    /** Format.id дорожки, которую показывает второй рендерер; null - вторых субтитров нет. */
    @Volatile var formatId: String? = null

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    internal var renderer: Renderer? = null

    internal val output = TextOutput { group -> _cues.value = group.cues }

    internal fun clear() {
        _cues.value = emptyList()
    }
}

@OptIn(UnstableApi::class)
internal class SecondaryTextRenderer(
    delegate: TextRenderer,
    private val secondary: SecondarySubtitles,
) : ForwardingRenderer(delegate) {

    private val capabilities = object : RendererCapabilities by delegate.capabilities {
        override fun supportsFormat(format: Format): Int =
            if (format.id != null && format.id == secondary.formatId) delegate.capabilities.supportsFormat(format)
            else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }

    override fun getCapabilities(): RendererCapabilities = capabilities
}

/**
 * На Android 9 часть приставок объявляет Opus, но не отдаёт кадры: видео стоит
 * с «Воспроизведение не началось (H.264)». Внешний Opus/Vorbis на API 28 и ниже
 * не отдаём платформенному декодеру, если в FFmpeg этого декодера нет.
 * Встроенная дорожка и новые телефоны не затрагиваются.
 */
@OptIn(UnstableApi::class)
private class ExternalOpusGuard(delegate: Renderer) : ForwardingRenderer(delegate) {
    private val capabilities = object : RendererCapabilities by delegate.capabilities {
        override fun supportsFormat(format: Format): Int {
            val mime = format.sampleMimeType
            val external = format.id?.contains(EXTERNAL_AUDIO_PREFIX) == true
            if (external && Build.VERSION.SDK_INT <= 28 &&
                (mime == MimeTypes.AUDIO_OPUS || mime == MimeTypes.AUDIO_VORBIS) &&
                !ffmpegCanDecode(mime)
            ) {
                return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
            }
            return delegate.capabilities.supportsFormat(format)
        }
    }

    override fun getCapabilities(): RendererCapabilities = capabilities
}

/** NextRenderersFactory (FFmpeg) + второй текстовый рендерер. */
@OptIn(UnstableApi::class)
internal class DualTextRenderersFactory(
    context: Context,
    private val secondary: SecondarySubtitles,
    private val night: NightAudioProcessor,
    private val passthrough: PassthroughGuard,
) : NextRenderersFactory(context) {
    private val codecContext = context

    override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory = ReleasedCodecAdapterFactory(codecContext)

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        GuardedAudioSink(
            DefaultAudioSink.Builder(context)
                // Обработка рассчитана на PCM 16 бит.
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(night))
                .build(),
            passthrough,
        )

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: java.util.ArrayList<Renderer>,
    ) {
        val before = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
        for (i in before until out.size) {
            if ("Ffmpeg" in out[i].javaClass.name) continue
            out[i] = ExternalOpusGuard(out[i])
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        // Второй рендерер - первым: при равной поддержке дорожка достаётся первому по списку.
        val renderer = SecondaryTextRenderer(TextRenderer(secondary.output, outputLooper), secondary)
        secondary.renderer = renderer
        out.add(renderer)
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    }
}
