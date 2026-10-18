package tv.p2160.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Schedule
import tv.p2160.core.settings.NightSchedule
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.Hyphens
import tv.p2160.core.api.Chapter
import tv.p2160.core.engine.PlayerUiState
import tv.p2160.core.engine.MediaReport
import tv.p2160.core.engine.ReportRow
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Flag
import tv.p2160.core.i18n.tr
import tv.p2160.core.engine.TrackOption
import kotlinx.coroutines.flow.first
import tv.p2160.core.settings.ResizeMode
import tv.p2160.core.settings.Settings

/** Название режима масштаба для панели и подсказки. */
@Composable
fun resizeLabel(mode: ResizeMode): String = when (mode) {
    ResizeMode.FIT -> tr("panel.fit")
    ResizeMode.FIT_WIDTH -> tr("panel.fit_width")
    ResizeMode.FIT_HEIGHT -> tr("panel.fit_height")
    ResizeMode.FILL -> tr("panel.fill")
    ResizeMode.ZOOM -> tr("panel.zoom")
}

enum class Panel(val titleKey: String) {
    AUDIO("player.audio"),
    SUBTITLES("player.subtitles"),
    SPEED("player.speed"),
    VIDEO("player.video"),
    CHAPTERS("player.chapters"),
    INFO("player.info"),
    SLEEP("player.sleep"),
}

val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

class PanelActions(
    val onSelectTrack: (TrackOption) -> Unit,
    val onDisableSubtitles: () -> Unit,
    val onAddSubtitle: () -> Unit,
    val onSubtitleDelay: (Long) -> Unit,
    val onSecondarySubtitle: (TrackOption?) -> Unit,
    val onNightMode: (Boolean) -> Unit,
    /** Изменение настроек расписания ночного звука (вкл/выкл, начало, конец в минутах от полуночи). */
    val onNightSchedule: (auto: Boolean, start: Int, end: Int) -> Unit,
    /** Задержка звука, мс (> 0 — звук позже картинки). */
    val onAudioDelay: (Int) -> Unit,
    /** Таймер сна: минуты или null — выключить. */
    val onSleepTimer: (Int?) -> Unit,
    /** Таймер сна «в конце серии». */
    val onSleepAtEnd: () -> Unit,
    /** Сводка по файлу строится по запросу (дорого: опрос кодеков). */
    val report: () -> MediaReport,
    val onSubtitleSize: (Float) -> Unit,
    val onSpeed: (Float) -> Unit,
    val onAutoVideo: () -> Unit,
    val onResize: (ResizeMode) -> Unit,
    val onChapter: (Chapter) -> Unit,
    val onMarkIntroStart: () -> Unit,
    val onMarkIntroEnd: () -> Unit,
    val onMarkCredits: () -> Unit,
    val onClearMarks: () -> Unit,
    /** Слой «Статистика» поверх видео. */
    val onStatsOverlay: (Boolean) -> Unit = {},
)

/** Боковая панель справа: на ТВ удобна для D-pad, на телефоне не перекрывает всё видео. */
@Composable
fun SidePanel(
    panel: Panel,
    state: PlayerUiState,
    settings: Settings,
    resizeMode: ResizeMode,
    actions: PanelActions,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val reveal = panelRevealIndex(panel, state, settings, resizeMode)
    // Выбранная строка ниже первого экрана (скорость 1× в ландшафте) в список ещё не попала,
    // и FocusRequester на ней нет. Сначала докручиваем, потом фокус.
    LaunchedEffect(panel) {
        val index = reveal
        if (index != null && index > 0) {
            val count = snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            if (index < count) {
                val info = listState.layoutInfo
                val item = info.visibleItemsInfo.find { it.index == index }
                val fully = item != null &&
                    item.offset >= info.viewportStartOffset &&
                    item.offset + item.size <= info.viewportEndOffset
                if (!fully) listState.scrollToItem(index)
                withFrameNanos { }
            }
        }
        runCatching { focus.requestFocus() }
    }
    val colors = MaterialTheme.colorScheme
    val listBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 12.dp

    // 42 % экрана, но не уже 300 и не шире 420 dp. Раньше widthIn стоял снаружи fillMaxWidth(0.42)
    // и 42 % считались уже от потолка 420 dp, а минимум 300 dp побеждал — панель всегда была 300 dp,
    // и подписи («Задержка звука») рвались посреди слова.
    BoxWithConstraints(modifier) {
    val panelWidth = sidePanelWidthDp(maxWidth.value).dp
    Column(
        Modifier
            .width(panelWidth)
            .fillMaxHeight()
            // Скругление делает слой панели снаружи (graphicsLayer.clip). Второй clip здесь
            // поднимал ещё один offscreen-буфер на каждый кадр прокрутки.
            // Непрозрачная подложка: полупрозрачный слой поверх SurfaceView перерисовывался каждый кадр.
            .background(colors.surface)
            .padding(top = 12.dp),
    ) {
        Text(
            tr(panel.titleKey),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        // Сводка по файлу опрашивает кодеки. Считать её один раз на файл, не на каждый кадр прокрутки.
        val infoReport = remember(panel, state.title, state.durationMs) {
            if (panel == Panel.INFO) actions.report() else null
        }
        // Нижний safeDrawing внутри окна списка, а не под ним: иначе последняя подсказка
        // оказывается под жестовой панелью, и список считает, что она уже видна.
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = listBottom),
        ) {
            when (panel) {
                Panel.AUDIO -> {
                    item(key = "night") {
                        PanelRow(
                            text = tr("panel.night_mode"),
                            secondary = tr("panel.night_mode_hint"),
                            selected = state.nightMode,
                            onClick = { actions.onNightMode(!state.nightMode) },
                            leading = Icons.Default.NightsStay,
                            switch = true,
                        )
                    }
                    item(key = "night-auto") {
                        PanelRow(
                            text = tr("panel.night_auto"),
                            secondary = tr("panel.night_auto_hint", NightSchedule.format(settings.nightStartMinute), NightSchedule.format(settings.nightEndMinute)),
                            selected = settings.nightAuto,
                            onClick = { actions.onNightSchedule(!settings.nightAuto, settings.nightStartMinute, settings.nightEndMinute) },
                            leading = Icons.Default.Schedule,
                            switch = true,
                        )
                    }
                    if (settings.nightAuto) {
                        item(key = "night-start") {
                            Stepper(
                                label = tr("panel.night_start"),
                                value = NightSchedule.format(settings.nightStartMinute),
                                onMinus = { actions.onNightSchedule(true, shiftMinutes(settings.nightStartMinute, -30), settings.nightEndMinute) },
                                onPlus = { actions.onNightSchedule(true, shiftMinutes(settings.nightStartMinute, 30), settings.nightEndMinute) },
                                onReset = { actions.onNightSchedule(true, 23 * 60, settings.nightEndMinute) },
                            )
                        }
                        item(key = "night-end") {
                            Stepper(
                                label = tr("panel.night_end"),
                                value = NightSchedule.format(settings.nightEndMinute),
                                onMinus = { actions.onNightSchedule(true, settings.nightStartMinute, shiftMinutes(settings.nightEndMinute, -30)) },
                                onPlus = { actions.onNightSchedule(true, settings.nightStartMinute, shiftMinutes(settings.nightEndMinute, 30)) },
                                onReset = { actions.onNightSchedule(true, settings.nightStartMinute, 10 * 60) },
                            )
                        }
                    }
                    item(key = "audio-delay") {
                        Stepper(
                            label = tr("panel.audio_delay"),
                            value = tr("panel.ms", state.audioDelayMs),
                            onMinus = { actions.onAudioDelay(state.audioDelayMs - 50) },
                            onPlus = { actions.onAudioDelay(state.audioDelayMs + 50) },
                            onReset = { actions.onAudioDelay(0) },
                        )
                    }
                    item(key = "audio-delay-hint") { Hint(tr("panel.audio_delay_hint")) }
                    if (state.audioTracks.isEmpty()) item(key = "no-audio", contentType = "hint") { Hint(tr("panel.no_audio")) }
                    items(state.audioTracks.withIndex().toList(), key = { "a${it.index}" }, contentType = { "track" }) { (i, t) ->
                        PanelRow(
                            text = t.label,
                            secondary = if (!t.supported) tr("panel.unsupported_audio") else null,
                            selected = t.selected,
                            onClick = { actions.onSelectTrack(t) },
                            modifier = if (t.selected || i == 0 && state.audioTracks.none { it.selected }) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }

                Panel.SLEEP -> {
                    val active = state.sleepRemainingMs != null || state.sleepAtEnd
                    item(key = "sleep-off") {
                        PanelRow(tr("panel.sleep_off"), selected = !active, onClick = { actions.onSleepTimer(null) },
                            modifier = if (!active) Modifier.focusRequester(focus) else Modifier)
                    }
                    items(listOf(15, 30, 45, 60, 90, 120), key = { "sleep$it" }, contentType = { "choice" }) { minutes ->
                        PanelRow(tr("panel.sleep_minutes", minutes), selected = false, onClick = { actions.onSleepTimer(minutes) })
                    }
                    item(key = "sleep-end") {
                        PanelRow(tr("panel.sleep_end"), selected = state.sleepAtEnd, onClick = actions.onSleepAtEnd,
                            modifier = if (state.sleepAtEnd) Modifier.focusRequester(focus) else Modifier)
                    }
                    state.sleepRemainingMs?.let { left ->
                        item(key = "sleep-left") { Hint(tr("panel.sleep_left", formatTime(left))) }
                    }
                    item(key = "sleep-hint") { Hint(tr("panel.sleep_hint")) }
                }

                Panel.SUBTITLES -> {
                    item(key = "off") {
                        PanelRow(
                            text = tr("panel.subs_off"),
                            leading = Icons.Default.SubtitlesOff,
                            selected = state.textDisabled || state.textTracks.none { it.selected },
                            onClick = actions.onDisableSubtitles,
                            modifier = Modifier.focusRequester(focus),
                        )
                    }
                    val primaryTracks = state.textTracks.filter { it.formatId == null || it.formatId != state.secondaryTextId }
                    items(primaryTracks.withIndex().toList(), key = { "t${it.index}" }, contentType = { "track" }) { (_, t) ->
                        PanelRow(
                            text = t.label,
                            selected = t.selected && !state.textDisabled,
                            onClick = { actions.onSelectTrack(t) },
                        )
                    }
                    // Вторые субтитры: показываются сверху, одновременно с основными.
                    if (state.textTracks.size > 1) {
                        item(key = "sec-title") { Hint(tr("panel.secondary_subs")) }
                        item(key = "sec-off") {
                            PanelRow(tr("panel.subs_off"), selected = state.secondaryTextId == null, onClick = { actions.onSecondarySubtitle(null) })
                        }
                        val candidates = state.textTracks.filter { !(it.selected && it.formatId != state.secondaryTextId) || state.textDisabled }
                        items(candidates.withIndex().toList(), key = { "s${it.index}" }, contentType = { "track" }) { (_, t) ->
                            PanelRow(
                                text = t.label,
                                selected = t.formatId != null && t.formatId == state.secondaryTextId,
                                onClick = { actions.onSecondarySubtitle(t) },
                            )
                        }
                    }
                    item(key = "add") {
                        PanelRow(tr("panel.subs_load"), selected = false, onClick = actions.onAddSubtitle, leading = Icons.Default.UploadFile)
                    }
                    item(key = "delay") {
                        Stepper(
                            label = tr("panel.delay"),
                            value = tr("panel.seconds", state.subtitleDelayMs / 1000f),
                            onMinus = { actions.onSubtitleDelay(state.subtitleDelayMs - 100) },
                            onPlus = { actions.onSubtitleDelay(state.subtitleDelayMs + 100) },
                            onReset = { actions.onSubtitleDelay(0) },
                        )
                    }
                    item(key = "size") {
                        val scale = settings.subtitleStyle.sizeScale
                        Stepper(
                            label = tr("panel.size"),
                            value = "${(scale * 100).toInt()}%",
                            onMinus = { actions.onSubtitleSize((scale - 0.1f).coerceAtLeast(0.5f)) },
                            onPlus = { actions.onSubtitleSize((scale + 0.1f).coerceAtMost(2.5f)) },
                            onReset = { actions.onSubtitleSize(1f) },
                        )
                    }
                }

                Panel.SPEED -> {
                    item(key = "fine") {
                        Stepper(
                            label = tr("panel.fine"),
                            value = "${UiFormat.speedFine(state.speed)}×",
                            onMinus = { actions.onSpeed(state.speed - 0.05f) },
                            onPlus = { actions.onSpeed(state.speed + 0.05f) },
                            onReset = { actions.onSpeed(1f) },
                        )
                    }
                    items(SPEED_PRESETS, key = { "s$it" }, contentType = { "choice" }) { s ->
                        val selected = kotlin.math.abs(state.speed - s) < 0.01f
                        PanelRow(
                            text = if (s == 1f) tr("panel.speed_normal") else "${formatSpeed(s)}×",
                            selected = selected,
                            onClick = { actions.onSpeed(s) },
                            modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }

                Panel.INFO -> {
                    val report = infoReport ?: actions.report()
                    item(key = "stats") {
                        PanelRow(
                            text = tr("panel.stats_overlay"),
                            secondary = tr("panel.stats_overlay_hint"),
                            selected = settings.statsOverlay,
                            onClick = { actions.onStatsOverlay(!settings.statsOverlay) },
                            modifier = Modifier.focusRequester(focus),
                            switch = true,
                        )
                    }
                    if (report.warnings.isNotEmpty()) {
                        item(key = "warn") {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                report.warnings.forEach { w ->
                                    Text("⚠ $w", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
                                }
                            }
                        }
                    }
                    report.sections.forEachIndexed { si, section ->
                        item(key = "sec$si") { Hint(section.title) }
                        items(section.rows.withIndex().toList(), key = { "r$si-${it.index}" }, contentType = { "info" }) { (ri, row) ->
                            InfoRow(row, modifier = Modifier.focusable())
                        }
                    }
                }

                Panel.CHAPTERS -> {
                    if (state.chapters.isEmpty()) {
                        item(key = "no-ch") { Hint(tr("panel.no_chapters")) }
                    }
                    items(state.chapters.withIndex().toList(), key = { "ch${it.index}" }, contentType = { "chapter" }) { (i, ch) ->
                        val current = i == state.chapterIndex
                        PanelRow(
                            text = ch.title ?: tr("player.chapter_n", i + 1),
                            secondary = formatTime(ch.startMs),
                            selected = current,
                            onClick = { actions.onChapter(ch) },
                            modifier = if (current) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                    item(key = "marks-title") { Hint(tr("panel.marks")) }
                    item(key = "marks-scope") {
                        Text(
                            state.seriesName?.let { tr("panel.marks_series", it) } ?: tr("panel.marks_file"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                    item(key = "m-is") {
                        PanelRow(
                            tr("panel.mark_intro_start"),
                            secondary = state.marks.introStartMs?.let(::formatTime),
                            selected = false, onClick = actions.onMarkIntroStart, leading = Icons.Default.FirstPage,
                            modifier = if (state.chapters.isEmpty()) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                    item(key = "m-ie") {
                        PanelRow(
                            tr("panel.mark_intro_end"),
                            secondary = state.marks.introEndMs?.let(::formatTime),
                            selected = false, onClick = actions.onMarkIntroEnd, leading = Icons.Default.LastPage,
                        )
                    }
                    item(key = "m-cr") {
                        PanelRow(
                            tr("panel.mark_credits"),
                            secondary = state.marks.creditsFromEndMs?.let { tr("panel.before_end", formatTime(it)) },
                            selected = false, onClick = actions.onMarkCredits, leading = Icons.Default.Flag,
                        )
                    }
                    if (!state.marks.isEmpty || state.marks.introStartMs != null) {
                        item(key = "m-clear") {
                            PanelRow(tr("panel.marks_clear"), selected = false, onClick = actions.onClearMarks, leading = Icons.Default.DeleteOutline)
                        }
                    }
                }

                Panel.VIDEO -> {
                    item(key = "resize-title") { Hint(tr("panel.scale")) }
                    items(ResizeMode.entries, key = { "r${it.name}" }, contentType = { "choice" }) { mode ->
                        PanelRow(
                            text = resizeLabel(mode),
                            selected = resizeMode == mode,
                            onClick = { actions.onResize(mode) },
                            modifier = if (resizeMode == mode) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                    if (state.videoTracks.isNotEmpty()) {
                        item(key = "q-title") { Hint(tr("panel.quality")) }
                        if (state.videoTracks.size > 1) {
                            item(key = "auto") { PanelRow(tr("panel.auto"), selected = false, onClick = actions.onAutoVideo) }
                        }
                        items(state.videoTracks.withIndex().toList(), key = { "v${it.index}" }, contentType = { "track" }) { (_, t) ->
                            PanelRow(
                                text = t.label,
                                secondary = if (!t.supported) tr("panel.unsupported_video") else null,
                                selected = t.selected,
                                onClick = { actions.onSelectTrack(t) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onReset: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Подпись на всю ширину панели, кнопки строкой ниже. В одной строке колонка подписи
    // сжималась до ширины четырёх букв и рвала «Задержка», «Точно», «Размер» посреди слова.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            label,
            color = colors.onSurface,
            style = MaterialTheme.typography.bodyLarge.copy(hyphens = Hyphens.None),
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 2.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.weight(1f))
            ControlButton(Icons.Default.Remove, tr("panel.less"), onMinus, size = 48.dp, tint = colors.onSurface)
            Text(
                value,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 72.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ControlButton(Icons.Default.Add, tr("panel.more"), onPlus, size = 48.dp, tint = colors.onSurface)
            ControlButton(Icons.Default.Restore, tr("panel.reset"), onReset, size = 48.dp, tint = colors.onSurfaceVariant)
        }
    }
}

fun formatSpeed(s: Float): String = UiFormat.speed(s)

/**
 * Строка, которую надо показать при открытии панели. Индекс совпадает с порядком `item` в [SidePanel].
 * null — выбранное и так в начале списка.
 */
internal fun panelRevealIndex(panel: Panel, state: PlayerUiState, settings: Settings, resizeMode: ResizeMode): Int? =
    when (panel) {
        Panel.SPEED -> speedRevealIndex(state.speed)
        Panel.VIDEO -> resizeRevealIndex(resizeMode)
        Panel.AUDIO -> audioTrackRevealIndex(
            nightAuto = settings.nightAuto,
            trackCount = state.audioTracks.size,
            selectedTrack = state.audioTracks.indexOfFirst { it.selected },
        )
        Panel.SUBTITLES -> {
            val primary = state.textTracks.filter { it.formatId == null || it.formatId != state.secondaryTextId }
            subtitleRevealIndex(state.textDisabled, primary.size, primary.indexOfFirst { it.selected })
        }
        Panel.CHAPTERS -> state.chapterIndex.takeIf { it in 0 until state.chapters.size }
        // «Выкл» — 0, шесть длительностей, затем «В конце серии».
        Panel.SLEEP -> if (state.sleepAtEnd) 7 else null
        Panel.INFO -> null
    }

/** Пресет скорости. 0 — шаговый регулятор сверху, дальше [SPEED_PRESETS]. */
internal fun speedRevealIndex(speed: Float): Int? {
    val i = SPEED_PRESETS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
    return if (i < 0) null else 1 + i
}

/** Режим масштаба. 0 — заголовок «Масштаб». */
internal fun resizeRevealIndex(mode: ResizeMode): Int = 1 + ResizeMode.entries.indexOf(mode).coerceAtLeast(0)

/**
 * Дорожка звука. Перед ней: ночной звук, расписание, при включённом расписании начало и конец,
 * задержка и подсказка. Нет выбранной дорожки — прокрутка не нужна.
 */
internal fun audioTrackRevealIndex(nightAuto: Boolean, trackCount: Int, selectedTrack: Int): Int? {
    if (selectedTrack !in 0 until trackCount) return null
    return 4 + (if (nightAuto) 2 else 0) + selectedTrack
}

/** «Выкл» — 0, дальше основные дорожки. Выключенные субтитры оставляют фокус на «Выкл». */
internal fun subtitleRevealIndex(disabled: Boolean, primaryCount: Int, selectedInPrimary: Int): Int {
    if (disabled || selectedInPrimary !in 0 until primaryCount) return 0
    return 1 + selectedInPrimary
}

private fun shiftMinutes(minute: Int, delta: Int): Int = ((minute + delta) % 1440 + 1440) % 1440

@Composable
private fun InfoRow(row: ReportRow, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(row.label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.size(12.dp))
        Text(
            row.value,
            color = when (row.level) {
                ReportRow.Level.OK -> Color(0xFF81C784)
                ReportRow.Level.WARN -> Color(0xFFFFB74D)
                ReportRow.Level.INFO -> colors.onSurface
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
