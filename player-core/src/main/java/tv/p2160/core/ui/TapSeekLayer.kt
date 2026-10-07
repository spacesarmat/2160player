package tv.p2160.core.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.p2160.core.i18n.tr
import kotlin.math.abs

/** Окно, в которое должен попасть второй тап, чтобы считаться двойным. */
private const val DOUBLE_TAP_MS = 300L

/** Пока серия активна, каждый новый тап с той же стороны добавляет ещё один шаг. */
private const val SERIES_MS = 800L

/**
 * Слой жестов поверх видео:
 * - одиночный тап — [onSingleTap] (показать/скрыть управление);
 * - двойной тап по центру — [onCenterDoubleTap] (пауза);
 * - двойной тап по левой/правой трети — перемотка на [stepMs], каждый следующий
 *   быстрый тап с той же стороны добавляет ещё [stepMs]: 3 тапа = 2 шага, 5 тапов = 4 шага;
 * - щипок двумя пальцами — [onPinch] с итоговым масштабом жеста (>1 — развели, <1 — свели).
 */
@Composable
fun TapSeekLayer(
    stepMs: Long,
    onSingleTap: () -> Unit,
    onCenterDoubleTap: () -> Unit,
    onSeek: (deltaMs: Long) -> Unit,
    onScrubStart: () -> Unit,
    /** Горизонтальный свайп: смещение пальца в долях ширины экрана (−1…1). */
    onScrub: (fraction: Float) -> Unit,
    onScrubEnd: () -> Unit,
    /** Удержание пальца: true — начать временное 2×, false — вернуть скорость. */
    onSpeedBoost: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onPinch: (zoom: Float) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var side by remember { mutableIntStateOf(0) }          // -1 — назад, 1 — вперёд, 0 — серии нет
    var steps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var pendingZone by remember { mutableIntStateOf(0) }
    val pending = remember { arrayOfNulls<Job>(2) }        // [0] — отложенный одиночный тап, [1] — скрытие подсказки
    val step by rememberUpdatedState(stepMs)
    val single by rememberUpdatedState(onSingleTap)
    val centerDouble by rememberUpdatedState(onCenterDoubleTap)
    val seek by rememberUpdatedState(onSeek)

    fun extendSeries() {
        pending[1]?.cancel()
        pending[1] = scope.launch {
            delay(SERIES_MS)
            side = 0
            steps = 0
        }
    }

    fun seekOnce(direction: Int) {
        steps++
        seek(direction * step)
        extendSeries()
    }

    fun handleTap(x: Float, width: Int) {
        val now = SystemClock.uptimeMillis()
        val zone = when {
            x < width / 3f -> -1
            x > width * 2f / 3f -> 1
            else -> 0
        }
        val sinceLast = now - lastTapAt
        lastTapAt = now

        // Продолжение серии с той же стороны.
        if (side != 0 && zone == side && sinceLast < SERIES_MS) {
            seekOnce(side)
            return
        }

        // Второй тап быстро после первого и в той же зоне — двойной.
        val firstTap = pending[0]
        if (firstTap?.isActive == true && sinceLast < DOUBLE_TAP_MS && zone == pendingZone) {
            firstTap.cancel()
            if (zone == 0) {
                centerDouble()
            } else {
                side = zone
                steps = 0
                seekOnce(zone)
            }
            return
        }

        // Первый тап: ждём, не станет ли он двойным.
        side = 0
        steps = 0
        pendingZone = zone
        pending[0]?.cancel()
        pending[0] = scope.launch {
            delay(DOUBLE_TAP_MS)
            single()
        }
    }

    val scrubStart by rememberUpdatedState(onScrubStart)
    val scrub by rememberUpdatedState(onScrub)
    val scrubEnd by rememberUpdatedState(onScrubEnd)
    val boost by rememberUpdatedState(onSpeedBoost)
    val pinch by rememberUpdatedState(onPinch)

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = viewConfiguration.touchSlop
                    val longPress = viewConfiguration.longPressTimeoutMillis
                    var dragging = false
                    var boosted = false
                    var pinching = false
                    var zoom = 1f
                    var last = down.position
                    while (true) {
                        // Ждём событие; если палец неподвижен дольше longPress — это удержание (2×).
                        val event = if (!dragging && !boosted && !pinching) {
                            withTimeoutOrNull(longPress) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            boosted = true
                            boost(true)
                            continue
                        }
                        // Второй палец — это щипок: отменяем перемотку/ускорение и копим масштаб до отпускания.
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || pinching) {
                            if (!pinching) {
                                pinching = true
                                if (dragging) { dragging = false; scrubEnd() }
                                if (boosted) { boosted = false; boost(false) }
                            }
                            if (pressed >= 2) zoom *= event.calculateZoom()
                            event.changes.forEach { it.consume() }
                            if (pressed == 0) break
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        last = change.position
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging && !boosted && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) {
                            dragging = true
                            scrubStart()
                        }
                        if (dragging) {
                            scrub(dx / size.width)
                            change.consume()
                        }
                    }
                    when {
                        pinching -> pinch(zoom)
                        boosted -> boost(false)
                        dragging -> scrubEnd()
                        (last - down.position).getDistance() < slop -> handleTap(last.x, size.width)
                    }
                }
            },
    ) {
        SeekBubble(visible = side == -1, forward = false, seconds = (steps * stepMs / 1000).toInt(), modifier = Modifier.align(Alignment.CenterStart))
        SeekBubble(visible = side == 1, forward = true, seconds = (steps * stepMs / 1000).toInt(), modifier = Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SeekBubble(visible: Boolean, forward: Boolean, seconds: Int, modifier: Modifier) {
    // Полукруг у края экрана, как в YouTube: скругление только с внутренней стороны.
    val shape = if (forward) RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    else RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    AnimatedVisibility(visible, modifier = modifier.fillMaxHeight().fillMaxWidth(0.34f), enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().clip(shape).background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    tr(if (forward) "player.seek_flash_forward" else "player.seek_flash_back", seconds),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
