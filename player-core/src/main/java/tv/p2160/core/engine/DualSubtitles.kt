package tv.p2160.core.engine

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Вторые субтитры (например, английские сверху и русские снизу).
 *
 * ExoPlayer выводит одну текстовую дорожку на рендерер, поэтому добавляем второй TextRenderer.
 * Он стоит перед основным и «умеет» только выбранную вторую дорожку: так при распределении дорожек
 * по рендерерам она достаётся ему, а все остальные — основному.
 */
@OptIn(UnstableApi::class)
class SecondarySubtitles {
    /** Format.id дорожки, которую показывает второй рендерер; null — вторых субтитров нет. */
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

/** NextRenderersFactory (FFmpeg) + второй текстовый рендерер. */
@OptIn(UnstableApi::class)
internal class DualTextRenderersFactory(
    context: Context,
    private val secondary: SecondarySubtitles,
    private val night: NightAudioProcessor,
    /** Ночной режим включён при старте: отключаем passthrough, чтобы звук шёл через обработку. */
    private val decodeAllAudio: Boolean,
) : NextRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            // Обработка рассчитана на PCM 16 бит.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(night))
            .apply {
                @Suppress("DEPRECATION")
                if (decodeAllAudio) setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            }
            .build()

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        // Второй рендерер — первым: при равной поддержке дорожка достаётся первому по списку.
        val renderer = SecondaryTextRenderer(TextRenderer(secondary.output, outputLooper), secondary)
        secondary.renderer = renderer
        out.add(renderer)
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
    }
}
