package tv.p2160.core.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
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
    val onAddAudio: () -> Unit = {},
    val onSubtitleDelay: (Long) -> Unit,
    val onSecondarySubtitle: (TrackOption?) -> Unit,
    val onNightMode: (Boolean) -> Unit,
    /** Изменение настроек расписания ночного звука (вкл/выкл, начало, конец в минутах от полуночи). */
    val onNightSchedule: (auto: Boolean, start: Int, end: Int) -> Unit,
    /** Задержка звука, мс (> 0 - звук позже картинки). */
    val onAudioDelay: (Int) -> Unit,
    /** Таймер сна: минуты или null - выключить. */
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

/**
 * Прокрутка в ноль, пока панель уже за краем. [PanelBody] на это не пересобирается:
 * открытие и закрытие этот сигнал не ждут и список не измеряют.
 * Если смещение уже ноль, обработчик ничего не вызывает.
 */
class PanelScrollReset {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    fun request() {
        requests.trySend(Unit)
    }

    suspend fun collect(block: suspend () -> Unit) {
        for (ignored in requests) block()
    }
}

/** Живой флаг «панель на экране». Чтение в лямбде фокуса, не в параметрах строк. */
internal class PanelChrome(var open: Boolean)

/** Боковая панель справа. Хост держит фокус, доступность и прокрутку при открытии.
 *  [PanelBody] не знает, открыта панель или нет: повторное открытие не пересобирает строки.
 *  Экран держит собранными «Аудио» и «Скорость» (они за краем, пока закрыты). Остальные
 *  снимаются после выезда: «Сведения» тяжёлые. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidePanel(
    panel: Panel,
    state: PlayerUiState,
    settings: Settings,
    resizeMode: ResizeMode,
    actions: PanelActions,
    scrollReset: PanelScrollReset,
    /** Панель на экране. Только хост: строки это не читают. */
    open: Boolean,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val reveal = panelRevealIndex(panel, state, settings, resizeMode)
    val listState = remember(panel) { LazyListState(0, 0) }
    val scrollState = remember(panel) { ScrollState(0) }
    val bring = remember { BringIntoViewRequester() }
    val revealPlace = remember { intArrayOf(0, 0) }
    val revealNow = remember { PanelRevealNow(reveal, false) }
    val chrome = remember { PanelChrome(open) }
    revealNow.index = reveal
    chrome.open = open
    // Сброс только по сигналу простоя, когда панель уже за краем. Открытие его не ждёт.
    LaunchedEffect(scrollReset) {
        scrollReset.collect {
            if (panelScrollAtTop(revealNow.lazy, listState, scrollState)) return@collect
            withFrameNanos { }
            if (chrome.open) return@collect
            resetPanelScroll(revealNow.lazy, listState, scrollState)
        }
    }
    // Фокус и доступность снаружи graphicsLayer: смена open не перезаписывает слой панели.
    Box(
        Modifier
            .focusGroup()
            .focusProperties {
                canFocus = open
                onEnter = { if (!open) cancelFocusChange() }
            }
            .semantics { if (!open) hideFromAccessibility() }
            .then(modifier),
    ) {
        PanelBody(
            panel = panel,
            state = state,
            settings = settings,
            resizeMode = resizeMode,
            actions = actions,
            focus = focus,
            listState = listState,
            scrollState = scrollState,
            bring = bring,
            reveal = reveal,
            revealPlace = revealPlace,
            revealNow = revealNow,
        )
    }
}

/** Строки панели. Параметры не включают открытость: смена open не должна переизмерять список. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelBody(
    panel: Panel,
    state: PlayerUiState,
    settings: Settings,
    resizeMode: ResizeMode,
    actions: PanelActions,
    focus: FocusRequester,
    listState: LazyListState,
    scrollState: ScrollState,
    bring: BringIntoViewRequester,
    reveal: Int?,
    revealPlace: IntArray,
    revealNow: PanelRevealNow,
) {
    val slots = PanelSlots()
    val infoReport = remember(panel, state.title, state.durationMs) {
        if (panel == Panel.INFO) actions.report() else null
    }
    slots.populate(panel, state, settings, resizeMode, actions, focus, infoReport)
    val lazy = panelUsesLazyList(slots.list.size)
    revealNow.lazy = lazy
    val colors = MaterialTheme.colorScheme
    val listBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding() + 12.dp
    val panelWidth = sidePanelWidthDp(LocalConfiguration.current.screenWidthDp.toFloat()).dp
    Column(
        Modifier
            .width(panelWidth)
            .fillMaxHeight()
            .background(colors.surface)
            .padding(top = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth().zIndex(1f).background(colors.surface)) {
            Text(
                tr(panel.titleKey),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            HorizontalDivider(color = colors.outlineVariant)
        }
        val listMod = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(bottom = listBottom)
            .clipToBounds()
        if (lazy) {
            LazyColumn(
                state = listState,
                modifier = listMod.testTag("panel-scroll"),
                contentPadding = PaddingValues(top = 4.dp),
            ) {
                slots.list.forEach { slot ->
                    item(key = slot.key, contentType = slot.contentType) { slot.content() }
                }
            }
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides panelBringIntoViewSpec) {
                // Метка на узле прокрутки, без layout-модификатора снаружи: иначе семантика сдвига на другом узле.
                Column(listMod.verticalScroll(scrollState).testTag("panel-scroll")) {
                    Spacer(Modifier.size(width = 0.dp, height = 4.dp))
                    for (index in slots.list.indices) {
                        val slot = slots.list[index]
                        key(slot.key) {
                            if (reveal != null && index == reveal && reveal > 0) {
                                Box(
                                    Modifier.bringIntoViewRequester(bring).onGloballyPositioned { coords ->
                                        revealPlace[0] = coords.positionInParent().y.roundToInt()
                                        revealPlace[1] = coords.size.height
                                    },
                                ) { slot.content() }
                            } else {
                                slot.content()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelSlots.populate(
    panel: Panel,
    state: PlayerUiState,
    settings: Settings,
    resizeMode: ResizeMode,
    actions: PanelActions,
    focus: FocusRequester,
    infoReport: MediaReport?,
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
                    item(key = "add-audio") {
                        PanelRow(tr("panel.subs_load"), selected = false, onClick = actions.onAddAudio, leading = Icons.Default.UploadFile)
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
                    // Не Slider: шаг 0.05 и чипы пресетов. У строки нет тени, размытия и ripple
                    // (indication = null). Дорогая первая запись уходит на кадр прогрева, не на открытие.
                    item(key = "fine") {
                        Stepper(
                            label = tr("panel.fine"),
                            value = "${UiFormat.speedFine(state.speed)}×",
                            onMinus = { actions.onSpeed(state.speed - 0.05f) },
                            onPlus = { actions.onSpeed(state.speed + 0.05f) },
                            onReset = { actions.onSpeed(1f) },
                            compact = true,
                        )
                    }
                    items(SPEED_PRESETS, key = { "s$it" }, contentType = { "choice" }) { s ->
                        val selected = kotlin.math.abs(state.speed - s) < 0.01f
                        PanelRow(
                            text = if (s == 1f) tr("panel.speed_normal") else "${formatSpeed(s)}×",
                            selected = selected,
                            onClick = { actions.onSpeed(s) },
                            modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                            compact = true,
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

private class PanelSlot(val key: Any, val contentType: Any, val content: @Composable () -> Unit)

private class PanelSlots {
    val list = mutableListOf<PanelSlot>()

    fun item(key: Any, contentType: Any = "row", content: @Composable () -> Unit) {
        list += PanelSlot(key, contentType, content)
    }

    fun <T> items(
        items: List<T>,
        key: (T) -> Any,
        contentType: (T) -> Any = { "row" },
        content: @Composable (T) -> Unit,
    ) {
        items.forEach { value -> item(key(value), contentType(value)) { content(value) } }
    }
}

/** Подпись панели: перенос по словам, без переносов по слогам. */
internal fun TextStyle.unbrokenWords(): TextStyle = copy(
    hyphens = Hyphens.None,
    lineBreak = LineBreak(
        strategy = LineBreak.Strategy.Simple,
        strictness = LineBreak.Strictness.Normal,
        wordBreak = LineBreak.WordBreak.Phrase,
    ),
)

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge.unbrokenWords(),
        softWrap = true,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onReset: () -> Unit,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (compact) {
        // Одна строка: «Точно» забирает оставшуюся ширину, кнопки 40 dp. Иначе 1× ниже сгиба
        // на альбомном экране высотой 1080 px.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                label,
                color = colors.onSurface,
                style = MaterialTheme.typography.bodyMedium.unbrokenWords(),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            ControlButton(Icons.Default.Remove, tr("panel.less"), onMinus, size = 40.dp, tint = colors.onSurface, compact = true)
            Text(
                value,
                color = colors.onSurface,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            ControlButton(Icons.Default.Add, tr("panel.more"), onPlus, size = 40.dp, tint = colors.onSurface, compact = true)
            ControlButton(Icons.Default.Restore, tr("panel.reset"), onReset, size = 40.dp, tint = colors.onSurfaceVariant, compact = true)
        }
    } else {
    // Подпись на всю ширину панели, кнопки строкой ниже. В одной строке колонка подписи
    // сжималась до ширины четырёх букв и рвала «Задержка», «Точно», «Размер» посреди слова.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            label,
            color = colors.onSurface,
            style = MaterialTheme.typography.bodyLarge.unbrokenWords(),
            softWrap = true,
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 2.dp),
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
}

fun formatSpeed(s: Float): String = UiFormat.speed(s)

/**
 * На сколько пикселей прокрутить список, чтобы строка оказалась целиком в окне.
 * Плюс - контент вверх. Уже видимую строку не двигает. Строка выше окна - только на величину обрезки.
 */
internal fun revealDelta(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Int {
    if (size <= 0 || viewportEnd <= viewportStart) return 0
    val itemEnd = offset + size
    if (offset >= viewportStart && itemEnd <= viewportEnd) return 0
    if (offset < viewportStart && itemEnd > viewportEnd) return 0
    if (itemEnd > viewportEnd) return itemEnd - viewportEnd
    return offset - viewportStart
}

/**
 * После [androidx.compose.foundation.lazy.LazyListState.scrollToItem] строка стоит у верхнего края.
 * Сколько пикселей отмотать назад, чтобы она села у нижнего края и верхние переключатели не уехали зря.
 */
internal fun revealPullBack(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Int {
    val room = viewportEnd - viewportStart - size
    if (room <= 0) return 0
    return (room - (offset - viewportStart)).coerceAtLeast(0)
}

private class PanelRevealNow(var index: Int?, var lazy: Boolean)

/**
 * Занимает ли панель место у полосы и подсказки.
 * Состав может оставаться после закрытия - это не повод держать отступ. Решает только открытость.
 */
internal fun panelReservesSpace(open: Boolean): Boolean = open

/** Правый отступ полосы и подсказки, dp. Ноль, пока панель закрыта; ширина панели плюс зазор, когда открыта. */
internal fun transportEndInsetDp(open: Boolean, screenWidthDp: Float): Float =
    if (panelReservesSpace(open)) sidePanelWidthDp(screenWidthDp) + sidePanelTransportGapDp else 0f

/**
 * Минимальный сдвиг, чтобы низ строки сел у нижнего края окна.
 * Открытие панели это не вызывает: список всегда с нуля, иначе прячутся переключатели сверху.
 */
internal fun scrollOffsetToReveal(itemOffset: Int, itemSize: Int, viewport: Int): Int {
    if (itemSize <= 0 || viewport <= 0 || itemOffset <= 0) return 0
    val itemEnd = itemOffset + itemSize
    if (itemEnd <= viewport) return 0
    if (itemSize >= viewport) return itemOffset
    return itemEnd - viewport
}

/** Уже в нуле: [scrollTo] даже без сдвига заново измеряет список. */
private fun panelScrollAtTop(lazy: Boolean, listState: LazyListState, scrollState: ScrollState): Boolean =
    if (lazy) listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
    else scrollState.value == 0

/** Пока панель за краем. Не крутить к выбранной строке: у звука это прячет переключатели. */
private suspend fun resetPanelScroll(lazy: Boolean, listState: LazyListState, scrollState: ScrollState) {
    if (panelScrollAtTop(lazy, listState, scrollState)) return
    if (lazy) listState.scrollToItem(0) else scrollState.scrollTo(0)
}

/** До скольких строк панель - обычная колонка, а не LazyColumn. */
internal const val PANEL_EAGER_ROW_LIMIT = 16

/** Длинный список (главы, много дорожек) остаётся ленивым. Короткая панель собирается сразу. */
internal fun panelUsesLazyList(rowCount: Int): Boolean = rowCount > PANEL_EAGER_ROW_LIMIT

/**
 * Сдвиг bring-into-view для короткой панели: 0, если строка уже целиком в окне,
 * иначе только обрезанный край. Совпадает с [revealDelta].
 */
internal fun panelBringIntoViewDistance(offset: Float, size: Float, containerSize: Float): Float {
    if (containerSize <= 0f) return 0f
    return revealDelta(offset.toInt(), size.toInt(), 0, containerSize.toInt()).toFloat()
}

@OptIn(ExperimentalFoundationApi::class)
private val panelBringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = snap()
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        panelBringIntoViewDistance(offset, size, containerSize)
}

/**
 * Индекс выбранной строки в порядке `item` панели. Открытие к ней не прокручивает.
 * null - выбранного нет или оно и так первое.
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
        // «Выкл» - 0, шесть длительностей, затем «В конце серии».
        Panel.SLEEP -> if (state.sleepAtEnd) 7 else null
        Panel.INFO -> null
    }

/** Пресет скорости. 0 - шаговый регулятор сверху, дальше [SPEED_PRESETS]. */
internal fun speedRevealIndex(speed: Float): Int? {
    val i = SPEED_PRESETS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
    return if (i < 0) null else 1 + i
}

/** Режим масштаба. 0 - заголовок «Масштаб». */
internal fun resizeRevealIndex(mode: ResizeMode): Int = 1 + ResizeMode.entries.indexOf(mode).coerceAtLeast(0)

/**
 * Дорожка звука. Перед ней: ночной звук, расписание, при включённом расписании начало и конец,
 * задержка и подсказка. Нет выбранной дорожки - прокрутка не нужна.
 */
internal fun audioTrackRevealIndex(nightAuto: Boolean, trackCount: Int, selectedTrack: Int): Int? {
    if (selectedTrack !in 0 until trackCount) return null
    return 4 + (if (nightAuto) 2 else 0) + selectedTrack
}

/** «Выкл» - 0, дальше основные дорожки. Выключенные субтитры оставляют фокус на «Выкл». */
internal fun subtitleRevealIndex(disabled: Boolean, primaryCount: Int, selectedInPrimary: Int): Int {
    if (disabled || selectedInPrimary !in 0 until primaryCount) return 0
    return 1 + selectedInPrimary
}

private fun shiftMinutes(minute: Int, delta: Int): Int = ((minute + delta) % 1440 + 1440) % 1440

@Composable
private fun InfoRow(row: ReportRow, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            row.label,
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium.unbrokenWords(),
            softWrap = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            row.value,
            color = when (row.level) {
                ReportRow.Level.OK -> Color(0xFF81C784)
                ReportRow.Level.WARN -> Color(0xFFFFB74D)
                ReportRow.Level.INFO -> colors.onSurface
            },
            style = MaterialTheme.typography.bodyMedium.unbrokenWords(),
            softWrap = true,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}
