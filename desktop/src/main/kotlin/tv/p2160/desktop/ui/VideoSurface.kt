package tv.p2160.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.withFrameNanos
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import tv.p2160.desktop.player.MpvPlayer
import tv.p2160.desktop.player.VideoFrame
import tv.p2160.desktop.player.shouldCopyFrame

@Composable
fun VideoSurface(player: MpvPlayer, modifier: Modifier = Modifier) {
    val frame by player.frames.collectAsState()
    val bitmap = remember(frame) { frame?.toBitmap() }
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        LaunchedEffect(maxWidth, maxHeight, density) {
            val w = with(density) { maxWidth.roundToPx() }
            val h = with(density) { maxHeight.roundToPx() }
            player.setSurfaceSize(w, h)
        }
        LaunchedEffect(player, maxWidth, maxHeight) {
            var lastPos = Double.NaN
            while (true) {
                withFrameNanos {
                    val playback = player.state.value
                    val hasFrame = player.frames.value != null
                    val moved = playback.positionSec != lastPos
                    val redraw = player.takeRedrawRequest()
                    if (shouldCopyFrame(playback.paused, hasFrame, moved, redraw)) {
                        player.renderNow()
                        lastPos = player.state.value.positionSec
                    }
                }
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun VideoFrame.toBitmap(): ImageBitmap {
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
    return SkiaImage.makeRaster(info, bgr0, width * 4).toComposeImageBitmap()
}
