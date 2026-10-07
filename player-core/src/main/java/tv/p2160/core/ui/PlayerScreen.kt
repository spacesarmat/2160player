package tv.p2160.core.ui

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import tv.p2160.core.engine.PlayerController
import tv.p2160.core.api.PlayerExtensions
import tv.p2160.core.engine.TimeInput
import tv.p2160.core.api.SegmentType
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.filled.KeyboardDoubleArrowRight
import androidx.compose.runtime.mutableLongStateOf
import tv.p2160.core.i18n.I18n
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.settings.PlayerSettings
import tv.p2160.core.settings.ResizeMode
import tv.p2160.core.settings.SubtitleEdge
import tv.p2160.core.settings.SubtitleStyle

/**
 * Экран плеера целиком. Можно встраивать в своё Compose-приложение:
 * создайте [PlayerController] и передайте его сюда.
 */
@Composable
fun PlayerScreen(
    controller: PlayerController,
    settingsStore: PlayerSettings,
    onBack: () -> Unit,
    onPickSubtitle: () -> Unit,
    inPictureInPicture: Boolean = false,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val settings by settingsStore.state.collectAsStateWithLifecycle()
    val strings by I18n.get(LocalContext.current).strings.collectAsStateWithLifecycle()
    val theme = PlayerThemes.byId(settings.themeId)
    val stepMs = settings.seekStepSeconds * 1000L

    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableStateOf(settings.resizeMode) }
    /** Подпись режима масштаба на пару секунд после переключения. */
    var resizeHint by remember { mutableStateOf<ResizeMode?>(null) }
    fun changeResize(mode: ResizeMode) {
        resizeMode = mode
        resizeHint = mode
        settingsStore.update { it.copy(resizeMode = mode) }
    }
    // Накопленная перемотка с пульта (показывается по центру: «+1:30»).
    var flashTotal by remember { mutableLongStateOf(0L) }
    var flashTick by remember { mutableIntStateOf(0) }
    // Перемотка свайпом / перетаскиванием полосы: целевая позиция и кадр-превью.
    var scrubBase by remember { mutableLongStateOf(0L) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubFrame by remember { mutableStateOf<Bitmap?>(null) }
    // Перемотка стрелками пульта с превью: цель копится, перематываем при отпускании.
    var keyScrubbing by remember { mutableStateOf(false) }
    // Ввод цифрами с пульта и диалог «Перейти ко времени».
    var digits by remember { mutableStateOf("") }
    var goToDialog by remember { mutableStateOf(false) }
    // Карточка «Следующая серия»: отменена для текущего элемента плейлиста.
    var nextDismissedFor by remember { mutableIntStateOf(-1) }
    // Плашка канала после переключения стрелками (IPTV).
    var zapTick by remember { mutableIntStateOf(0) }
    var zapVisible by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    // Когда панель скрыта, фокус держит сам экран — иначе пульт «теряется» и кнопки не работают.
    val rootFocus = remember { FocusRequester() }

    fun poke() { interaction++ }
    fun show() { controlsVisible = true; poke() }

    // Автоскрытие элементов управления во время воспроизведения.
    LaunchedEffect(controlsVisible, interaction, state.isPlaying, panel) {
        if (controlsVisible && state.isPlaying && panel == null) {
            delay(4_000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, panel) {
        if (controlsVisible && panel == null) runCatching { playFocus.requestFocus() }
        else if (!controlsVisible && panel == null) runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(flashTick) { if (flashTotal != 0L) { delay(900); flashTotal = 0 } }
    // Кадр для превью подгружаем с небольшой задержкой, чтобы не дёргать FFmpeg на каждый пиксель.
    LaunchedEffect(scrubTarget?.div(2_000)) {
        val target = scrubTarget ?: run { scrubFrame = null; return@LaunchedEffect }
        delay(120)
        controller.frameAt(target)?.let { scrubFrame = it }
    }
    LaunchedEffect(scrubTarget, keyScrubbing) {
        if (keyScrubbing) {
            delay(1_500)
            scrubTarget?.let(controller::seekTo)
            scrubTarget = null
            keyScrubbing = false
        }
    }
    LaunchedEffect(zapTick) {
        if (zapTick == 0) return@LaunchedEffect
        zapVisible = true
        delay(4_000)
        zapVisible = false
    }
    fun zap(delta: Int) { controller.switchChannel(delta); zapTick++ }
    // Цифры: в эфире — номер канала в списке, иначе — время.
    fun commit(value: String) {
        if (state.liveTv) {
            val target = value.toIntOrNull()?.minus(1)?.takeIf { it in 0 until state.playlistSize } ?: return
            if (target != state.playlistIndex) zap(target - state.playlistIndex)
        } else {
            commitDigits(value, state.durationMs, controller::seekTo)
        }
    }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        commit(digits)
        digits = ""
    }
    LaunchedEffect(state.resumedFromMs) { if (state.resumedFromMs != null) { delay(7_000); controller.dismissResumeHint() } }
    LaunchedEffect(state.ended, settings.autoPlayNext) {
        if (state.ended) controlsVisible = true
    }

    BackHandler {
        when {
            panel != null -> panel = null
            else -> onBack()
        }
    }

    fun keyScrub(delta: Long) {
        if (!keyScrubbing) {
            keyScrubbing = true
            scrubBase = state.positionMs
        }
        val max = state.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        scrubTarget = ((scrubTarget ?: scrubBase) + delta).coerceIn(0, max)
    }

    fun commitKeyScrub() {
        scrubTarget?.let(controller::seekTo)
        scrubTarget = null
        keyScrubbing = false
    }

    fun cancelKeyScrub() {
        scrubTarget = null
        keyScrubbing = false
    }

    fun seekWithFlash(delta: Long) {
        controller.seekBy(delta)
        flashTotal += delta
        flashTick++
    }

    // Отрезок, для которого показываем кнопку «Пропустить» (титры с переходом к следующей серии — отдельно).
    val nearEnd = !state.isLive && !state.liveTv && state.hasNext && state.durationMs > 60_000 && state.durationMs - state.positionMs in 1..15_000
    val creditsWithNext = !state.liveTv && state.hasNext && (state.activeSegment?.type == SegmentType.CREDITS || nearEnd)
    val showNextCard = creditsWithNext && nextDismissedFor != state.playlistIndex && !inPictureInPicture && settings.autoPlayNext
    val skippable = state.activeSegment?.takeIf { !creditsWithNext }

    CompositionLocalProvider(LocalStrings provides strings) {
    P2160Theme(theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocus)
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp) {
                        if (keyScrubbing && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) {
                            commitKeyScrub()
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (keyScrubbing) {
                        when (e.key) {
                            Key.Back, Key.Escape -> { cancelKeyScrub(); return@onPreviewKeyEvent true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { commitKeyScrub(); return@onPreviewKeyEvent true }
                        }
                    }
                    // Медиаклавиши пульта работают всегда.
                    when (e.key) {
                        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { controller.playPause(); show(); return@onPreviewKeyEvent true }
                        Key.MediaFastForward -> { seekWithFlash(stepMs * 3); return@onPreviewKeyEvent true }
                        Key.MediaRewind -> { seekWithFlash(-stepMs * 3); return@onPreviewKeyEvent true }
                        Key.MediaNext -> { if (state.playlistSize > 1) controller.next() else controller.nextChapter(); return@onPreviewKeyEvent true }
                        Key.MediaPrevious -> { if (state.playlistSize > 1) controller.previous() else controller.previousChapter(); return@onPreviewKeyEvent true }
                        Key.ChannelUp -> if (state.liveTv) { zap(1); return@onPreviewKeyEvent true }
                        Key.ChannelDown -> if (state.liveTv) { zap(-1); return@onPreviewKeyEvent true }
                    }
                    if (panel != null || inPictureInPicture || goToDialog) return@onPreviewKeyEvent false
                    // Цифры: одна — проценты, несколько — время.
                    digitOf(e.key)?.let { d -> digits = (digits + d).takeLast(6); return@onPreviewKeyEvent true }
                    if (digits.isNotEmpty()) {
                        when (e.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                commit(digits); digits = ""; return@onPreviewKeyEvent true
                            }
                            Key.Back, Key.Escape, Key.Backspace -> { digits = ""; return@onPreviewKeyEvent true }
                        }
                    }
                    if (controlsVisible) { poke(); return@onPreviewKeyEvent false }
                    // Удержание стрелки ускоряет перемотку; обрабатываем каждый 3-й повтор, чтобы не улетать.
                    val repeat = e.nativeKeyEvent.repeatCount
                    val step = stepMs * when {
                        repeat < 4 -> 1
                        repeat < 12 -> 3
                        else -> 6
                    }
                    val throttled = repeat > 0 && repeat % 3 != 0
                    // Управление скрыто: стрелки перематывают, OK — пауза (или «Пропустить»), вверх/вниз — показать панель.
                    // Телеканалы: влево/вправо — панель (перематывать эфир некуда), вверх/вниз — переключение.
                    if (state.liveTv && (e.key == Key.DirectionLeft || e.key == Key.DirectionRight)) { show(); return@onPreviewKeyEvent true }
                    when (e.key) {
                        Key.DirectionLeft -> { if (!throttled) keyScrub(-step); true }
                        Key.DirectionRight -> { if (!throttled) keyScrub(step); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val seg = skippable
                            if (seg != null) controller.skip(seg) else { controller.playPause(); show() }
                            true
                        }
                        // Телеканалы: вверх — предыдущий, вниз — следующий.
                        Key.DirectionUp -> { if (state.liveTv && state.playlistSize > 1) zap(-1) else show(); true }
                        Key.DirectionDown -> { if (state.liveTv && state.playlistSize > 1) zap(1) else show(); true }
                        Key.Menu -> { show(); true }
                        else -> false
                    }
                },
        ) {
            VideoSurface(controller, settings.subtitleStyle, resizeMode, controlsVisible && !inPictureInPicture)
            // Частота экрана под частоту кадров (ТВ).
            MatchDisplayFrameRate(controller, settings.frameRateMatching && state.hasVideo, state.videoFrameRate)
            // Статистика поверх видео: собирается, только пока слой включён и виден.
            val showStats = settings.statsOverlay && !inPictureInPicture
            LaunchedEffect(showStats) { controller.setStatsEnabled(showStats) }
            val stats by controller.stats.collectAsStateWithLifecycle()
            stats?.takeIf { showStats }?.let {
                StatsOverlay(it, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = if (controlsVisible) 88.dp else 16.dp))
            }
            if (state.secondaryTextId != null) SecondarySubtitleLayer(controller, settings.subtitleStyle)

            // Слой жестов: тап — показать/скрыть, двойной (и более) тап по краям — накопительная перемотка.
            if (!inPictureInPicture) {
                TapSeekLayer(
                    stepMs = stepMs,
                    onSingleTap = {
                        if (panel != null) panel = null
                        else { controlsVisible = !controlsVisible; poke() }
                    },
                    onCenterDoubleTap = controller::playPause,
                    onSeek = { delta -> controller.seekBy(delta); if (controlsVisible) poke() },
                    onScrubStart = { if (!state.liveTv) { scrubBase = state.positionMs; scrubTarget = state.positionMs } },
                    onScrub = scrub@{ fraction ->
                        // Эфир телеканала свайпом не перематываем.
                        if (state.liveTv) return@scrub
                        // Ширина экрана = 20 % длительности, но не меньше 90 с и не больше 10 мин.
                        val range = (state.durationMs / 5).coerceIn(90_000, 600_000)
                        scrubTarget = (scrubBase + (fraction * range).toLong()).coerceIn(0, state.durationMs.coerceAtLeast(0))
                    },
                    onScrubEnd = { scrubTarget?.let(controller::seekTo); scrubTarget = null },
                    onSpeedBoost = controller::setSpeedBoost,
                    // Щипок: развести пальцы — заполнить экран (с обрезкой), свести — вписать целиком.
                    onPinch = pinch@{ zoom ->
                        val mode = when {
                            zoom > 1.15f -> ResizeMode.ZOOM
                            zoom < 0.87f -> ResizeMode.FIT
                            else -> return@pinch
                        }
                        changeResize(mode)
                    },
                )
            }

            if (state.isBuffering && state.error == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = theme.accent, strokeWidth = 4.dp)
            }

            if (flashTotal != 0L) {
                Text(
                    formatDelta(flashTotal),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.align(Alignment.Center)
                        .clip(RoundedCornerShape(16.dp)).background(theme.scrim)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }

            if (!state.hasVideo && !inPictureInPicture) AudioOnlyArt(state.title, theme)

            AnimatedVisibility(
                visible = controlsVisible && !inPictureInPicture,
                enter = fadeIn(), exit = fadeOut(),
            ) {
                Controls(
                    state = state,
                    theme = theme,
                    stepMs = stepMs,
                    playFocus = playFocus,
                    onBack = onBack,
                    onPlayPause = { controller.playPause(); poke() },
                    onSeek = { controller.seekTo(it); poke() },
                    onSeekBy = { seekWithFlash(it); poke() },
                    onNext = controller::next,
                    onPrevious = controller::previous,
                    onPanel = { panel = it },
                    onInteraction = ::poke,
                    onScrub = { target -> if (target != null && scrubTarget == null) scrubBase = state.positionMs; scrubTarget = target },
                    onGoTo = { goToDialog = true },
                    onNextChapter = { controller.nextChapter(); poke() },
                    onPreviousChapter = { controller.previousChapter(); poke() },
                )
            }

            if (!inPictureInPicture) {
                scrubTarget?.let { target ->
                    ScrubPreview(
                        targetMs = target,
                        deltaMs = target - scrubBase,
                        frame = scrubFrame,
                        chapter = state.chapters.lastOrNull { it.startMs <= target },
                        theme = theme,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (state.speedBoost) {
                    SpeedBoostBadge(theme, Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 24.dp))
                }
                if (digits.isNotEmpty()) DigitEntryOverlay(digits, theme, Modifier.align(Alignment.Center))
                if (zapVisible && !controlsVisible && state.liveTv) {
                    ChannelBanner(
                        number = state.playlistIndex + 1,
                        title = state.title,
                        subtitle = state.subtitle,
                        theme = theme,
                        modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(start = 24.dp, top = 24.dp),
                    )
                }

                val bottomOffset = if (controlsVisible) 110.dp else 32.dp
                skippable?.let { seg ->
                    if (panel == null) {
                        SkipButton(
                            type = seg.type,
                            onClick = { controller.skip(seg); poke() },
                            theme = theme,
                            takeFocus = !controlsVisible,
                            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(end = 24.dp, bottom = bottomOffset),
                        )
                    }
                }
                if (showNextCard && panel == null) {
                    var left by remember(state.playlistIndex) { mutableIntStateOf(NEXT_COUNTDOWN_SEC) }
                    LaunchedEffect(state.playlistIndex, state.isPlaying) {
                        while (state.isPlaying && left > 0) { delay(1_000); left-- }
                        if (left == 0) controller.next()
                    }
                    NextEpisodeCard(
                        secondsLeft = left,
                        totalSeconds = NEXT_COUNTDOWN_SEC,
                        onPlayNow = controller::next,
                        onCancel = { nextDismissedFor = state.playlistIndex },
                        theme = theme,
                        modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(end = 24.dp, bottom = bottomOffset),
                    )
                }
            }

            if (goToDialog) {
                GoToTimeDialog(
                    durationMs = state.durationMs,
                    onGo = { controller.seekTo(it); goToDialog = false; poke() },
                    onDismiss = { goToDialog = false },
                )
            }

            resizeHint?.let { mode ->
                if (!inPictureInPicture) {
                    LaunchedEffect(mode) { delay(1_500); resizeHint = null }
                    Text(
                        resizeLabel(mode),
                        color = theme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 72.dp)
                            .clip(RoundedCornerShape(14.dp)).background(theme.surface.copy(alpha = 0.92f))
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }

            state.smartHint?.let { hint ->
                if (!inPictureInPicture) {
                    LaunchedEffect(hint) { delay(5_000); controller.dismissSmartHint() }
                    Text(
                        hint,
                        color = theme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(start = 24.dp, bottom = 120.dp).widthIn(max = 560.dp)
                            .clip(RoundedCornerShape(14.dp)).background(theme.surface.copy(alpha = 0.92f))
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            if (state.warnings.isNotEmpty() && !inPictureInPicture) {
                LaunchedEffect(state.warnings) { delay(8_000); controller.dismissWarnings() }
                Column(
                    Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = 24.dp, top = 72.dp).widthIn(max = 520.dp)
                        .clip(RoundedCornerShape(16.dp)).background(theme.surface.copy(alpha = 0.95f))
                        .clickable { panel = Panel.INFO; controller.dismissWarnings() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    state.warnings.take(3).forEach { Text("⚠ $it", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodyMedium) }
                    Text(tr("info.more"), color = theme.accent, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // Таймер сна идёт — небольшая подпись сверху (в PiP не показываем).
            if ((state.sleepRemainingMs != null || state.sleepAtEnd) && !inPictureInPicture) {
                Text(
                    state.sleepRemainingMs?.let { tr("player.sleep_in", formatTime(it)) } ?: tr("player.sleep_at_end"),
                    color = theme.onSurface.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = if (controlsVisible) 76.dp else 16.dp, end = 24.dp)
                        .clip(RoundedCornerShape(12.dp)).background(theme.surface.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            state.resumedFromMs?.let { from ->
                if (!inPictureInPicture) {
                    Row(
                        Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(top = 72.dp)
                            .clip(RoundedCornerShape(24.dp)).background(theme.surface.copy(alpha = 0.95f))
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("player.resumed", formatTime(from)), color = theme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = controller::restartFromBeginning) { Text(tr("player.start_over"), color = theme.accent) }
                    }
                }
            }

            state.error?.let { message ->
                Column(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp))
                        .background(theme.surface).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(message, color = theme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = controller::retry) { Text(tr("player.retry"), color = theme.accent) }
                        TextButton(onClick = onBack) { Text(tr("player.close"), color = theme.muted) }
                    }
                }
            }

            AnimatedVisibility(
                visible = panel != null,
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                panel?.let { current ->
                    SidePanel(
                        panel = current,
                        state = state,
                        settings = settings,
                        resizeMode = resizeMode,
                        actions = PanelActions(
                            onSelectTrack = controller::select,
                            onDisableSubtitles = controller::disableSubtitles,
                            onAddSubtitle = { panel = null; onPickSubtitle() },
                            onSubtitleDelay = controller::setSubtitleDelay,
                            onSecondarySubtitle = controller::setSecondarySubtitle,
                            onNightMode = controller::setNightMode,
                            report = controller::report,
                            onNightSchedule = { auto, start, end ->
                                settingsStore.update { it.copy(nightAuto = auto, nightStartMinute = start, nightEndMinute = end) }
                                controller.onNightScheduleChanged()
                            },
                            onAudioDelay = controller::setAudioDelay,
                            onSleepTimer = { minutes -> controller.setSleepTimer(minutes); panel = null },
                            onSleepAtEnd = { controller.setSleepAtEndOfItem(); panel = null },
                            onSubtitleSize = { v -> settingsStore.update { it.copy(subtitleStyle = it.subtitleStyle.copy(sizeScale = v)) } },
                            onSpeed = controller::setSpeed,
                            onAutoVideo = controller::autoVideo,
                            onResize = ::changeResize,
                            onChapter = { controller.seekToChapter(it); panel = null },
                            onMarkIntroStart = controller::markIntroStart,
                            onMarkIntroEnd = controller::markIntroEnd,
                            onMarkCredits = controller::markCreditsStart,
                            onClearMarks = controller::clearMarks,
                            onStatsOverlay = { v -> settingsStore.update { it.copy(statsOverlay = v) } },
                        ),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun Controls(
    state: tv.p2160.core.engine.PlayerUiState,
    theme: PlayerTheme,
    stepMs: Long,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onPanel: (Panel) -> Unit,
    onInteraction: () -> Unit,
    onScrub: (Long?) -> Unit,
    onGoTo: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousChapter: () -> Unit,
) {
    val hasChapters = state.chapters.size > 1
    Box(Modifier.fillMaxSize()) {
        // Градиенты сверху и снизу, чтобы текст читался на светлом видео.
        Box(Modifier.fillMaxWidth().size(height = 140.dp, width = 0.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(theme.scrim, Color.Transparent))))
        Box(Modifier.fillMaxWidth().size(height = 200.dp, width = 0.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, theme.scrim))))

        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Телефон в портрете: кнопки не помещаются рядом с названием — переносим их во второй ряд (с прокруткой).
            val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
            val actionButtons: @Composable () -> Unit = {
                val extraActions by PlayerExtensions.actions.collectAsStateWithLifecycle()
                val context = LocalContext.current
                extraActions.forEach { action ->
                    ControlButton(
                        action.icon, LocalStrings.current[action.label],
                        { action.onClick(context, PlayerExtensions.nowPlaying.value) },
                        accent = theme.accent,
                    )
                }
                ControlButton(Icons.Default.Info, tr("player.info"), { onPanel(Panel.INFO) }, accent = theme.accent)
                ControlButton(Icons.Default.Bookmarks, tr("player.chapters"), { onPanel(Panel.CHAPTERS) }, accent = theme.accent)
                ControlButton(Icons.Default.Audiotrack, tr("player.audio"), { onPanel(Panel.AUDIO) }, accent = theme.accent)
                ControlButton(Icons.Default.Subtitles, tr("player.subtitles"), { onPanel(Panel.SUBTITLES) }, accent = theme.accent)
                ControlButton(Icons.Default.Speed, tr("player.speed"), { onPanel(Panel.SPEED) }, accent = theme.accent)
                ControlButton(Icons.Default.Bedtime, tr("player.sleep"), { onPanel(Panel.SLEEP) }, accent = theme.accent)
                ControlButton(Icons.Default.AspectRatio, tr("player.video"), { onPanel(Panel.VIDEO) }, accent = theme.accent)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlButton(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), onBack, accent = theme.accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(state.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val position = if (state.playlistSize > 1) tr("player.playlist_position", state.playlistIndex + 1, state.playlistSize) else null
                    val secondLine = listOfNotNull(position, state.subtitle).joinToString("  ·  ")
                    if (secondLine.isNotEmpty()) {
                        Text(secondLine, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!narrow) actionButtons()
            }
            if (narrow) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) { actionButtons() }
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.playlistSize > 1) ControlButton(Icons.Default.SkipPrevious, tr("player.previous"), onPrevious, accent = theme.accent)
                if (hasChapters) ControlButton(Icons.Default.KeyboardDoubleArrowLeft, tr("player.chapter_previous"), onPreviousChapter, accent = theme.accent)
                ControlButton(Icons.Default.Replay10, tr("player.seek_back", (stepMs / 1000).toInt()), { onSeekBy(-stepMs) }, size = 56.dp, accent = theme.accent)
                ControlButton(
                    icon = when {
                        state.ended -> Icons.Default.Replay
                        state.isPlaying -> Icons.Default.Pause
                        else -> Icons.Default.PlayArrow
                    },
                    contentDescription = if (state.isPlaying) tr("player.pause") else tr("player.play"),
                    onClick = onPlayPause,
                    size = 76.dp,
                    accent = theme.accent,
                    modifier = Modifier.focusRequester(playFocus),
                )
                ControlButton(Icons.Default.Forward10, tr("player.seek_forward", (stepMs / 1000).toInt()), { onSeekBy(stepMs) }, size = 56.dp, accent = theme.accent)
                if (hasChapters) ControlButton(Icons.Default.KeyboardDoubleArrowRight, tr("player.chapter_next"), onNextChapter, accent = theme.accent)
                if (state.playlistSize > 1) ControlButton(Icons.Default.SkipNext, tr("player.next"), onNext, enabled = state.hasNext, accent = theme.accent)
            }

            Spacer(Modifier.weight(1f))

            // Эфир без окна перемотки (или телеканал) — без полосы, только «Эфир».
            if (!state.isLive || (!state.liveTv && state.durationMs > 0)) SeekBar(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                bufferedMs = state.bufferedMs,
                stepMs = stepMs,
                onSeek = onSeek,
                onInteraction = onInteraction,
                accent = theme.accent,
                chapterStartsMs = state.chapters.map { it.startMs },
                segments = state.segments,
                onScrub = onScrub,
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // Нажатие на время — «Перейти ко времени».
                Text(
                    formatTime(state.positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onGoTo).padding(horizontal = 4.dp, vertical = 2.dp),
                )
                state.chapters.getOrNull(state.chapterIndex)?.let { ch ->
                    Text(
                        "  ·  " + (ch.title ?: tr("player.chapter_n", state.chapterIndex + 1)),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.speed != 1f) {
                    Text("${formatSpeed(state.speed)}×  ", color = theme.accent, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.isLive) {
                    LiveBadge()
                } else {
                    Text(
                        if (state.durationMs > 0) "−${formatTime(state.durationMs - state.positionMs)} / ${formatTime(state.durationMs)}" else if (state.isBuffering) "" else tr("player.live"),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Красная плашка «Эфир». */
@Composable
private fun LiveBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFFD32F2F)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
        Spacer(Modifier.width(6.dp))
        Text(tr("player.live"), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Номер, название канала и текущая передача — после переключения с пульта. */
@Composable
private fun ChannelBanner(number: Int, title: String, subtitle: String?, theme: PlayerTheme, modifier: Modifier = Modifier) {
    Row(
        modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(16.dp)).background(theme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number.toString(), color = theme.accent, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = theme.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = theme.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun AudioOnlyArt(title: String, theme: PlayerTheme) {
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(theme.accent.copy(alpha = 0.35f), theme.background))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(Icons.Default.Audiotrack, null, tint = theme.accent, modifier = Modifier.size(96.dp))
            Spacer(Modifier.size(16.dp))
            Text(title, color = theme.onSurface, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 32.dp))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(controller: PlayerController, style: SubtitleStyle, resizeMode: ResizeMode, controlsVisible: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                setShutterBackgroundColor(AndroidColor.BLACK)
                setKeepContentOnPlayerReset(true)
                player = controller.player
                keepScreenOn = true
            }
        },
        update = { view ->
            if (view.player !== controller.player) view.player = controller.player
            view.resizeMode = when (resizeMode) {
                ResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                ResizeMode.FIT_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                ResizeMode.FIT_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            view.subtitleView?.apply {
                setStyle(
                    CaptionStyleCompat(
                        Color(style.textColor).toArgb(),
                        Color(style.backgroundColor).toArgb(),
                        AndroidColor.TRANSPARENT,
                        when (style.edge) {
                            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                            SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                        },
                        AndroidColor.BLACK,
                        null,
                    )
                )
                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.sizeScale)
                setApplyEmbeddedStyles(!style.overrideEmbeddedStyles)
                setApplyEmbeddedFontSizes(!style.overrideEmbeddedStyles)
                // Поднимаем субтитры, когда видна панель управления, чтобы она их не закрывала.
                setBottomPaddingFraction(if (controlsVisible) style.bottomPadding + 0.2f else style.bottomPadding)
            }
        },
    )
}

private const val NEXT_COUNTDOWN_SEC = 10

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** Одна цифра — переход на N×10 % длительности, несколько — ко времени (1230 → 12:30). */
private fun commitDigits(digits: String, durationMs: Long, seekTo: (Long) -> Unit) {
    val target = if (digits.length == 1) durationMs * digits.toInt() / 10 else TimeInput.parse(digits)
    target?.takeIf { durationMs <= 0 || it <= durationMs }?.let(seekTo)
}

/** Слой вторых субтитров: те же настройки стиля, но у верхнего края и чуть мельче. */
@OptIn(UnstableApi::class)
@Composable
private fun SecondarySubtitleLayer(controller: PlayerController, style: SubtitleStyle) {
    val cues by controller.secondaryCues.collectAsStateWithLifecycle()
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> SubtitleView(ctx) },
        update = { view ->
            view.setStyle(
                CaptionStyleCompat(
                    Color(style.textColor).toArgb(),
                    Color(style.backgroundColor).toArgb(),
                    AndroidColor.TRANSPARENT,
                    when (style.edge) {
                        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                        SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    },
                    AndroidColor.BLACK,
                    null,
                )
            )
            view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.sizeScale * 0.9f)
            view.setApplyEmbeddedStyles(false)
            // Текстовые реплики поднимаем наверх; картинки (PGS) оставляем на их месте.
            view.setCues(cues.map { cue ->
                if (cue.bitmap != null) cue
                else cue.buildUpon().setLine(0.04f, androidx.media3.common.text.Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(androidx.media3.common.text.Cue.ANCHOR_TYPE_START).build()
            })
        },
    )
}
