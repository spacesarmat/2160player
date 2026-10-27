package tv.p2160.core.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import tv.p2160.core.i18n.tr
import tv.p2160.core.api.SkipSegment
import androidx.compose.material.icons.filled.Check

/**
 * Ворота нажатий скрытого оверлея. Один объект на всё затухание: чтение в лямбде клика,
 * не в композиции, чтобы показ и скрытие не пересобирали кнопки.
 */
internal class InteractionGate(var open: Boolean = true)

private val AlwaysInteractive = InteractionGate(true)

internal val LocalInteractionGate = staticCompositionLocalOf { AlwaysInteractive }

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
    /** Панель скорости: кнопки меньше 48 dp, чтобы «Точно» и 1× влезли в альбомный экран. */
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "btnScale")
    val target = if (compact) size else size.coerceAtLeast(48.dp)
    val gate = LocalInteractionGate.current
    Box(
        modifier = modifier
            .size(target)
            .scale(scale)
            .clip(CircleShape)
            .background(if (focused) accent else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = {
                if (gate.open) onClick()
            })
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
            },
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
            modifier = Modifier.size(target * 0.58f),
        )
    }
}

/**
 * Позиция и буфер полосы. Пишутся из коллектора, читаются только в отрисовке своего слоя:
 * тик не пересобирает полосу и не измеряет её родителя.
 */
internal class TransportClock(positionMs: Long = 0L, bufferedMs: Long = 0L) {
    val positionMs = mutableLongStateOf(positionMs)
    val bufferedMs = mutableLongStateOf(bufferedMs)

    /** Позиция под пальцем или стрелкой. null - на экране время воспроизведения. */
    val scrubMs = mutableStateOf<Long?>(null)

    fun publish(position: Long, buffered: Long) {
        if (positionMs.longValue == position && bufferedMs.longValue == buffered) return
        Snapshot.withMutableSnapshot {
            positionMs.longValue = position
            bufferedMs.longValue = buffered
        }
    }

    /** Что рисовать. Читает состояние: вызывать из отрисовки, не из композиции. */
    fun shownMs(): Long {
        val scrub = scrubMs.value
        return if (scrub != null) scrub else positionMs.longValue
    }

    fun preview(ms: Long?) {
        if (scrubMs.value != ms) scrubMs.value = ms
    }
}

/**
 * Полоса перемотки: тянется пальцем, на пульте - влево/вправо с шагом [stepMs].
 * Позиция приходит параметром и попадает в [TransportClock] после композиции, без нового измерения.
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
    /** Начала глав - рисуются разрывами на полосе. */
    chapterStartsMs: List<Long> = emptyList(),
    /** Пропускаемые отрезки (вступление, титры) - подсвечиваются на полосе. */
    segments: List<SkipSegment> = emptyList(),
    /** Позиция под пальцем во время перетаскивания (для превью), null - перетаскивание закончено. */
    onScrub: (Long?) -> Unit = {},
) {
    val clock = remember { TransportClock(positionMs, bufferedMs) }
    SideEffect { clock.publish(positionMs, bufferedMs) }
    SeekBar(
        clock = clock,
        durationMs = durationMs,
        stepMs = stepMs,
        onSeek = onSeek,
        onInteraction = onInteraction,
        modifier = modifier,
        accent = accent,
        chapterStartsMs = chapterStartsMs,
        segments = segments,
        onScrub = onScrub,
    )
}

/**
 * Та же полоса, но позиция читается в слое прогресса, не в композиции.
 * Подложка, главы и отрезки этот слой не перерисовывают.
 */
@Composable
internal fun SeekBar(
    clock: TransportClock,
    durationMs: Long,
    stepMs: Long,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    chapterStartsMs: List<Long> = emptyList(),
    segments: List<SkipSegment> = emptyList(),
    onScrub: (Long?) -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val dragFraction = remember { mutableStateOf<Float?>(null) }
    val keyTarget = remember { mutableStateOf<Long?>(null) }
    val gate = LocalInteractionGate.current
    val duration = durationMs.coerceAtLeast(1)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .focusable(interactionSource = interaction)
            .onKeyEvent { e ->
                if (!gate.open || durationMs <= 0) return@onKeyEvent false
                val arrow = e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                if (e.type == KeyEventType.KeyUp) {
                    val target = keyTarget.value ?: return@onKeyEvent false
                    if (!arrow) return@onKeyEvent false
                    Snapshot.withMutableSnapshot {
                        keyTarget.value = null
                        clock.preview(null)
                    }
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
                val next = ((keyTarget.value ?: clock.shownMs()) + delta).coerceIn(0, durationMs)
                Snapshot.withMutableSnapshot {
                    keyTarget.value = next
                    clock.preview(next)
                }
                onScrub(next)
                onInteraction()
                true
            }
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    if (!gate.open) return@detectTapGestures
                    onSeek((o.x / size.width * duration).toLong())
                    onInteraction()
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        if (gate.open) {
                            val fraction = (o.x / size.width).coerceIn(0f, 1f)
                            val ms = (fraction * duration).toLong()
                            Snapshot.withMutableSnapshot {
                                dragFraction.value = fraction
                                clock.preview(ms)
                            }
                            onScrub(ms)
                            onInteraction()
                        }
                    },
                    onDragEnd = {
                        val fraction = dragFraction.value
                        Snapshot.withMutableSnapshot {
                            dragFraction.value = null
                            clock.preview(null)
                        }
                        onScrub(null)
                        if (gate.open) fraction?.let { onSeek((it * duration).toLong()) }
                    },
                    onDragCancel = {
                        Snapshot.withMutableSnapshot {
                            dragFraction.value = null
                            clock.preview(null)
                        }
                        onScrub(null)
                    },
                ) { change, _ ->
                    if (gate.open) {
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ms = (fraction * duration).toLong()
                        Snapshot.withMutableSnapshot {
                            dragFraction.value = fraction
                            clock.preview(ms)
                        }
                        onScrub(ms)
                        onInteraction()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Подложка не читает позицию: тик её не записывает заново.
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val active = focused || dragFraction.value != null || keyTarget.value != null
            val trackH = if (active) 8.dp.toPx() else 5.dp.toPx()
            val y = size.height / 2 - trackH / 2
            val r = CornerRadius(trackH / 2)
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(0f, y), Size(size.width, trackH), r)
        }
        // Буфер, пройденное и ползунок - свой слой. Родительская полоса из-за тика не измеряется.
        Canvas(Modifier.fillMaxWidth().height(36.dp).graphicsLayer {}) {
            val active = focused || dragFraction.value != null || keyTarget.value != null
            val trackH = if (active) 8.dp.toPx() else 5.dp.toPx()
            val y = size.height / 2 - trackH / 2
            val r = CornerRadius(trackH / 2)
            val drag = dragFraction.value
            val key = keyTarget.value
            val fraction = when {
                drag != null -> drag
                key != null -> (key.toFloat() / duration).coerceIn(0f, 1f)
                durationMs <= 0L -> 0f
                else -> (clock.positionMs.longValue.toFloat() / duration).coerceIn(0f, 1f)
            }
            val buffered = (clock.bufferedMs.longValue.toFloat() / duration).coerceIn(0f, 1f)
            drawRoundRect(Color.White.copy(alpha = 0.45f), Offset(0f, y), Size(size.width * buffered, trackH), r)
            segments.forEach { s ->
                val start = s.startMs.toFloat() / duration * size.width
                val end = (s.endMs ?: durationMs).toFloat() / duration * size.width
                drawRect(Color(0xFF7DD3FC).copy(alpha = 0.55f), Offset(start, y), Size((end - start).coerceAtLeast(0f), trackH))
            }
            drawRoundRect(accent, Offset(0f, y), Size(size.width * fraction, trackH), r)
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

/** Самая широкая подпись времени: табличные цифры, ширина не пляшет на тике. */
internal const val TIME_SAMPLE = "88:88:88"

/** «−часы / длительность» той же ширины, что и худший случай. Минус - U+2212, как на экране. */
internal const val REMAINING_SAMPLE = "−88:88:88 / 88:88:88"

/**
 * Ширина прошедшего времени под текущую длительность: `m:ss`, `mm:ss` или `h:mm:ss`.
 * Считается при смене длительности, не на каждом тике. Цифры - «8»: с `tnum` все одной ширины.
 */
internal fun elapsedTimeSample(durationMs: Long): String {
    if (durationMs <= 0L) return "8:88"
    val totalSec = durationMs / 1000
    val hours = totalSec / 3600
    return when {
        hours >= 1 -> "8".repeat(hours.toString().length) + ":88:88"
        totalSec >= 600 -> "88:88"
        else -> "8:88"
    }
}

/** «−остаток / длительность» той же ширины, что [elapsedTimeSample] для этой длительности. */
internal fun remainingTimeSample(durationMs: Long): String {
    val body = elapsedTimeSample(durationMs)
    return "−$body / $body"
}

/** Горизонталь глифа в коробке фиксированной ширины. Конец - у правого края, начало - у левого. */
internal fun tickTextX(boxWidthPx: Float, textWidthPx: Float, alignEnd: Boolean): Float =
    if (alignEnd) (boxWidthPx - textWidthPx).coerceAtLeast(0f) else 0f

/**
 * Подпись фиксированной ширины. Текст измеряется заранее по [sample] и рисуется в своём слое:
 * смена [clock] не измеряет родителя и не перезаписывает полосу целиком.
 */
@Composable
internal fun FixedTickText(
    clock: TransportClock,
    sample: String,
    style: TextStyle,
    color: Color,
    textKey: Any,
    label: (TransportClock) -> String,
    modifier: Modifier = Modifier,
    /** Остаток прижат к концу полосы. Прошедшее - к началу. */
    alignEnd: Boolean = false,
) {
    val measurer = rememberTextMeasurer()
    val resolved = remember(style, color) { style.merge(TextStyle(fontFeatureSettings = "tnum", color = color)) }
    val density = LocalDensity.current
    val sampleLayout = remember(measurer, resolved, sample, density) {
        measurer.measure(sample, style = resolved, maxLines = 1, softWrap = false, constraints = TickConstraints)
    }
    Box(
        modifier
            .width(with(density) { sampleLayout.size.width.toDp() })
            .height(with(density) { sampleLayout.size.height.toDp() })
            .graphicsLayer {}
            .then(TickDrawElement(clock, measurer, resolved, textKey, label, alignEnd)),
    )
}

private val TickConstraints = Constraints(maxWidth = Constraints.Infinity)

private class TickDrawElement(
    val clock: TransportClock,
    val measurer: TextMeasurer,
    val style: TextStyle,
    val textKey: Any,
    val label: (TransportClock) -> String,
    val alignEnd: Boolean,
) : ModifierNodeElement<TickDrawNode>() {
    override fun create(): TickDrawNode = TickDrawNode(clock, measurer, style, label, alignEnd)

    override fun update(node: TickDrawNode) {
        node.clock = clock
        node.measurer = measurer
        node.style = style
        node.label = label
        node.alignEnd = alignEnd
        // Чтение позиции только внутри observeReads, иначе тик подпишет композицию.
        node.observe()
    }

    override fun equals(other: Any?): Boolean = other is TickDrawElement &&
        clock == other.clock && measurer == other.measurer && style == other.style &&
        textKey == other.textKey && alignEnd == other.alignEnd

    override fun hashCode(): Int {
        var result = clock.hashCode()
        result = 31 * result + measurer.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + textKey.hashCode()
        result = 31 * result + alignEnd.hashCode()
        return result
    }
}

/**
 * Рисует строку и обновляет описание для TalkBack без measure. Позиция читается в [observeReads],
 * не в композиции: тик инвалидирует только этот узел.
 */
private class TickDrawNode(
    var clock: TransportClock,
    var measurer: TextMeasurer,
    var style: TextStyle,
    var label: (TransportClock) -> String,
    var alignEnd: Boolean,
) : Modifier.Node(), DrawModifierNode, SemanticsModifierNode, ObserverModifierNode {
    private var description: String = ""
    private var layout: TextLayoutResult? = null

    override fun onAttach() {
        observe()
    }

    override fun onObservedReadsChanged() {
        observe()
    }

    fun observe() {
        if (!isAttached) return
        observeReads {
            val next = label(clock)
            if (next == description && layout != null) return@observeReads
            description = next
            layout = measurer.measure(next, style = style, maxLines = 1, softWrap = false, constraints = TickConstraints)
            invalidateDraw()
            invalidateSemantics()
        }
    }

    override fun ContentDrawScope.draw() {
        val text = layout ?: return
        drawText(text, topLeft = Offset(tickTextX(size.width, text.size.width.toFloat(), alignEnd), 0f))
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        contentDescription = description
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
    /** Переключатель (ночной звук), а не пункт списка с одной галочкой. */
    switch: Boolean = false,
    /** Пресет скорости: ниже обычной строки, чтобы 1× был виден вместе с «Точно». */
    compact: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val switched = switch
    val on = selected
    val stateText = when {
        switched && on -> tr("a11y.on")
        switched -> tr("a11y.off")
        on -> tr("a11y.selected")
        else -> tr("a11y.unselected")
    }
    val choice = if (switched) {
        Modifier.toggleable(
            value = on,
            interactionSource = interaction,
            indication = null,
            role = Role.Switch,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.selectable(
            selected = on,
            interactionSource = interaction,
            indication = null,
            role = Role.RadioButton,
            onClick = onClick,
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 2.dp)
            .heightIn(min = if (compact) 32.dp else 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    focused -> colors.primary.copy(alpha = 0.28f)
                    selected -> colors.primary.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .then(if (focused) Modifier.border(2.dp, colors.primary, RoundedCornerShape(12.dp)) else Modifier)
            // Роль на внешнем узле, который склеивает текст. Иначе TalkBack читает строку без состояния.
            .semantics(mergeDescendants = true) {
                stateDescription = stateText
                if (switched) {
                    role = Role.Switch
                    toggleableState = if (on) ToggleableState.On else ToggleableState.Off
                } else {
                    role = Role.RadioButton
                    this.selected = on
                }
            }
            .then(choice)
            .padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 12.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(leading, null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f).padding(end = if (switched) 12.dp else 0.dp)) {
                androidx.compose.material3.Text(
                    text,
                    color = if (selected && !switched) colors.primary else colors.onSurface,
                    style = (if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge).unbrokenWords(),
                    softWrap = true,
                )
                if (secondary != null) {
                    androidx.compose.material3.Text(
                        secondary,
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.unbrokenWords(),
                        softWrap = true,
                    )
                }
            }
            if (switched) {
                // Индикатор, как в настройках: нажатие обрабатывает вся строка (удобно с пульта).
                androidx.compose.material3.Switch(
                    checked = on,
                    onCheckedChange = null,
                    modifier = Modifier.focusProperties { canFocus = false },
                )
            } else if (selected) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.Check, null,
                    tint = colors.primary, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Пока длина файла неизвестна и воспроизведение ещё не сдвинулось с нуля. */
internal const val UNKNOWN_TIME = "--:--"

/** Прошедшее время. Неизвестная длина и нулевая позиция - не «0:00» и не «ЭФИР». */
internal fun transportElapsedText(durationKnown: Boolean, positionMs: Long): String =
    if (durationKnown || positionMs > 0L) formatTime(positionMs) else UNKNOWN_TIME

fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
