package tv.p2160.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.p2160.core.api.SkipSegment
import androidx.compose.material.icons.filled.Check

/**
 * Кнопка, одинаково удобная для пальца и пульта: заметная подсветка фокуса, лёгкое увеличение.
 */
@Composable
fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    accent: Color = MaterialTheme.colorScheme.primary,
    tint: Color = Color.White,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "btnScale")
    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(if (focused) accent else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> tint.copy(alpha = 0.35f)
                focused -> MaterialTheme.colorScheme.onPrimary
                else -> tint
            },
            modifier = Modifier.size(size * 0.58f),
        )
    }
}

/**
 * Полоса перемотки: тянется пальцем, на пульте — влево/вправо с шагом [stepMs].
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    stepMs: Long,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    /** Начала глав — рисуются разрывами на полосе. */
    chapterStartsMs: List<Long> = emptyList(),
    /** Пропускаемые отрезки (вступление, титры) — подсвечиваются на полосе. */
    segments: List<SkipSegment> = emptyList(),
    /** Позиция под пальцем во время перетаскивания (для превью), null — перетаскивание закончено. */
    onScrub: (Long?) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var keyTarget by remember { mutableStateOf<Long?>(null) }
    val duration = durationMs.coerceAtLeast(1)
    val fraction = dragFraction ?: keyTarget?.let { (it.toFloat() / duration).coerceIn(0f, 1f) }
        ?: if (durationMs <= 0) 0f else (positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / duration).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .focusable(interactionSource = interaction)
            .onKeyEvent { e ->
                if (durationMs <= 0) return@onKeyEvent false
                val arrow = e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                if (e.type == KeyEventType.KeyUp) {
                    val target = keyTarget ?: return@onKeyEvent false
                    if (!arrow) return@onKeyEvent false
                    keyTarget = null
                    onScrub(null)
                    onSeek(target)
                    return@onKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Удержание стрелки ускоряет перемотку: 1× → 3× → 6× шага.
                val repeat = e.nativeKeyEvent.repeatCount
                val step = stepMs * when {
                    repeat < 4 -> 1
                    repeat < 12 -> 3
                    else -> 6
                }
                if (!arrow) return@onKeyEvent false
                val delta = if (e.key == Key.DirectionLeft) -step else step
                keyTarget = ((keyTarget ?: positionMs) + delta).coerceIn(0, durationMs)
                onScrub(keyTarget)
                onInteraction()
                true
            }
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    onSeek((o.x / size.width * duration).toLong())
                    onInteraction()
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragFraction = (o.x / size.width).coerceIn(0f, 1f)
                        onScrub((dragFraction!! * duration).toLong())
                        onInteraction()
                    },
                    onDragEnd = {
                        dragFraction?.let { onSeek((it * duration).toLong()) }
                        dragFraction = null
                        onScrub(null)
                    },
                    onDragCancel = { dragFraction = null; onScrub(null) },
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onScrub((dragFraction!! * duration).toLong())
                    onInteraction()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val active = focused || dragFraction != null
            val trackH = if (active) 8.dp.toPx() else 5.dp.toPx()
            val y = size.height / 2 - trackH / 2
            val r = CornerRadius(trackH / 2)
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, trackH), r)
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * buffered, trackH), r)
            segments.forEach { s ->
                val start = s.startMs.toFloat() / duration * size.width
                val end = (s.endMs ?: durationMs).toFloat() / duration * size.width
                drawRect(Color(0xFF7DD3FC).copy(alpha = 0.55f), Offset(start, y), Size((end - start).coerceAtLeast(0f), trackH))
            }
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction, trackH), r)
            // Разрывы на границах глав.
            val gap = 3.dp.toPx()
            chapterStartsMs.forEach { ms ->
                if (ms <= 0 || ms >= durationMs) return@forEach
                val x = ms.toFloat() / duration * size.width
                drawRect(Color.Black.copy(alpha = 0.85f), Offset(x - gap / 2, y - 1), Size(gap, trackH + 2))
            }
            val thumb = if (active) 11.dp.toPx() else 7.dp.toPx()
            drawCircle(accent, thumb, Offset(size.width * fraction, size.height / 2))
            if (focused) drawCircle(Color.White, thumb, Offset(size.width * fraction, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}

/** Строка списка в боковой панели (дорожки, скорости, настройки). */
@Composable
fun PanelRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    leading: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> colors.primary.copy(alpha = 0.28f)
                    selected -> colors.primary.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .then(if (focused) Modifier.border(2.dp, colors.primary, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                androidx.compose.material3.Text(
                    text,
                    color = if (selected) colors.primary else colors.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (secondary != null) {
                    androidx.compose.material3.Text(
                        secondary,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (selected) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Check, null,
                    tint = colors.primary, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
