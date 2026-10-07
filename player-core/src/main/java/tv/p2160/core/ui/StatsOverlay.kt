package tv.p2160.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import tv.p2160.core.engine.FrameRateMatcher
import tv.p2160.core.engine.MediaReporter
import tv.p2160.core.engine.PlaybackStats
import tv.p2160.core.i18n.tr
import java.util.Locale

/** Activity, в которой показан плеер (для режима дисплея). */
internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Частота экрана под видео: пока [enabled] и частота кадров известна — режим дисплея с подходящей
 * частотой; при выходе из плеера — режим по умолчанию.
 */
@Composable
internal fun MatchDisplayFrameRate(controller: tv.p2160.core.engine.PlayerController, enabled: Boolean, fps: Float) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) { onDispose { FrameRateMatcher.reset(activity) } }
    LaunchedEffect(enabled, fps) {
        if (enabled && fps > 0f) FrameRateMatcher.apply(activity, controller.player, fps, controller::onDisplaySwitching)
        else FrameRateMatcher.reset(activity)
    }
}

/** Слой «Статистика» поверх видео (моноширинный текст на полупрозрачной подложке). */
@Composable
internal fun StatsOverlay(stats: PlaybackStats, modifier: Modifier = Modifier) {
    val activity = LocalContext.current.findActivity()
    val refresh = activity?.let(FrameRateMatcher::refreshRate) ?: 0f
    val lines = buildList {
        if (stats.videoWidth > 0) {
            val fps = if (stats.frameRate > 0) " · ${fmt(stats.frameRate)} fps" else ""
            add("${tr("stats.video")}: ${stats.videoWidth}×${stats.videoHeight} ${stats.videoCodec.orEmpty()}$fps")
            stats.videoDecoder?.let { add("  ${decoderLabel(it)}") }
            add("${tr("stats.frames")}: ${stats.renderedFrames} · ${tr("stats.dropped")} ${stats.droppedFrames}")
        }
        val audio = buildList {
            stats.audioDecoder?.let { add(decoderLabel(it)) }
            if (stats.audioPassthrough) add("passthrough")
            if (stats.audioOffload) add("offload")
        }
        if (audio.isNotEmpty()) add("${tr("stats.audio")}: ${audio.joinToString(" · ")}")
        val target = if (stats.bufferTargetBytes > 0) "/${mb(stats.bufferTargetBytes)}" else ""
        add("${tr("stats.buffer")}: ${fmt(stats.bufferMs / 1000f)} s · ${mb(stats.bufferBytes)}$target MB")
        if (stats.bandwidthBps > 0) add("${tr("stats.network")}: ${fmt(stats.bandwidthBps / 1_000_000f)} Mbit/s")
        val display = buildList {
            if (refresh > 0) add("${fmt(refresh)} Hz")
            if (stats.tunneling) add(tr("stats.tunneling"))
        }
        if (display.isNotEmpty()) add("${tr("stats.display")}: ${display.joinToString(" · ")}")
        add("CPU: ${stats.cpuPercent.toInt()}% · RAM ${stats.memoryMb} MB (Java ${stats.heapMb}, native ${stats.nativeHeapMb})")
    }
    Column(
        modifier
            .widthIn(max = 520.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        lines.forEach { Text(it, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp) }
    }
}

@Composable
private fun decoderLabel(name: String): String =
    "$name (${if (MediaReporter.isSoftware(name)) tr("stats.software") else tr("stats.hardware")})"

private fun fmt(v: Float): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
private fun mb(bytes: Long): String = (bytes / (1024 * 1024)).toString()
