package tv.p2160.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
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
import tv.p2160.core.settings.ResizeMode
import tv.p2160.core.settings.Settings
import java.util.Locale

enum class Panel(val titleKey: String) {
    AUDIO("player.audio"),
    SUBTITLES("player.subtitles"),
    SPEED("player.speed"),
    VIDEO("player.video"),
    CHAPTERS("player.chapters"),
    INFO("player.info"),
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
    LaunchedEffect(panel) { runCatching { focus.requestFocus() } }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier
            .fillMaxHeight()
            .widthIn(min = 300.dp, max = 420.dp)
            .fillMaxWidth(0.42f)
            .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .background(colors.surface.copy(alpha = 0.96f))
            .padding(vertical = 16.dp),
    ) {
        Text(
            tr(panel.titleKey),
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth()) {
            when (panel) {
                Panel.AUDIO -> {
                    item(key = "night") {
                        PanelRow(
                            text = tr("panel.night_mode"),
                            secondary = tr("panel.night_mode_hint"),
                            selected = state.nightMode,
                            onClick = { actions.onNightMode(!state.nightMode) },
                            leading = Icons.Default.NightsStay,
                        )
                    }
                    item(key = "night-auto") {
                        PanelRow(
                            text = tr("panel.night_auto"),
                            secondary = tr("panel.night_auto_hint", NightSchedule.format(settings.nightStartMinute), NightSchedule.format(settings.nightEndMinute)),
                            selected = settings.nightAuto,
                            onClick = { actions.onNightSchedule(!settings.nightAuto, settings.nightStartMinute, settings.nightEndMinute) },
                            leading = Icons.Default.Schedule,
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
                    if (state.audioTracks.isEmpty()) item { Hint(tr("panel.no_audio")) }
                    items(state.audioTracks.withIndex().toList(), key = { "a${it.index}" }) { (i, t) ->
                        PanelRow(
                            text = t.label,
                            secondary = if (!t.supported) tr("panel.unsupported_audio") else null,
                            selected = t.selected,
                            onClick = { actions.onSelectTrack(t) },
                            modifier = if (t.selected || i == 0 && state.audioTracks.none { it.selected }) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
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
                    items(primaryTracks.withIndex().toList(), key = { "t${it.index}" }) { (_, t) ->
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
                        items(candidates.withIndex().toList(), key = { "s${it.index}" }) { (_, t) ->
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
                            value = "%.2f×".format(Locale.US, state.speed),
                            onMinus = { actions.onSpeed(state.speed - 0.05f) },
                            onPlus = { actions.onSpeed(state.speed + 0.05f) },
                            onReset = { actions.onSpeed(1f) },
                        )
                    }
                    items(SPEED_PRESETS, key = { "s$it" }) { s ->
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
                    val report = actions.report()
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
                        items(section.rows.withIndex().toList(), key = { "r$si-${it.index}" }) { (ri, row) ->
                            InfoRow(row, modifier = if (si == 0 && ri == 0) Modifier.focusRequester(focus).focusable() else Modifier.focusable())
                        }
                    }
                }

                Panel.CHAPTERS -> {
                    if (state.chapters.isEmpty()) {
                        item(key = "no-ch") { Hint(tr("panel.no_chapters")) }
                    }
                    items(state.chapters.withIndex().toList(), key = { "ch${it.index}" }) { (i, ch) ->
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
                    items(ResizeMode.entries, key = { "r${it.name}" }) { mode ->
                        PanelRow(
                            text = when (mode) {
                                ResizeMode.FIT -> tr("panel.fit")
                                ResizeMode.FILL -> tr("panel.fill")
                                ResizeMode.ZOOM -> tr("panel.zoom")
                            },
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
                        items(state.videoTracks.withIndex().toList(), key = { "v${it.index}" }) { (_, t) ->
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
        ControlButton(Icons.Default.Remove, tr("panel.less"), onMinus, size = 40.dp, tint = colors.onSurface)
        Text(value, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        ControlButton(Icons.Default.Add, tr("panel.more"), onPlus, size = 40.dp, tint = colors.onSurface)
        ControlButton(Icons.Default.Restore, tr("panel.reset"), onReset, size = 40.dp, tint = colors.onSurfaceVariant)
        Spacer(Modifier.size(4.dp))
    }
}

fun formatSpeed(s: Float): String =
    if (s % 1f == 0f) s.toInt().toString() else "%.2f".format(Locale.US, s).trimEnd('0').trimEnd('.')

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
