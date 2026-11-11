package tv.p2160.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tv.p2160.desktop.media.formatClock
import tv.p2160.desktop.media.formatRemaining
import tv.p2160.desktop.media.shouldOfferResume
import tv.p2160.desktop.player.MpvPlayer
import tv.p2160.desktop.player.PlayState

@Composable
fun PlayerScreen(
    player: MpvPlayer,
    title: String,
    resumeSec: Double?,
    live: Boolean,
    onBack: () -> Unit,
    onSavePosition: (Double) -> Unit,
    onSaveVolume: (Int) -> Unit,
    onAddSubtitle: () -> Unit,
    onAddAudio: () -> Unit,
) {
    val state by player.state.collectAsState()
    var controls by remember { mutableStateOf(true) }
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var askedResume by remember { mutableStateOf(false) }
    var showAudio by remember { mutableStateOf(false) }
    var showSubs by remember { mutableStateOf(false) }

    LaunchedEffect(state.fileLoaded, state.durationSec, resumeSec, live) {
        if (askedResume || live || !state.fileLoaded || resumeSec == null) return@LaunchedEffect
        if (state.durationSec <= 0) return@LaunchedEffect
        askedResume = true
        if (shouldOfferResume(resumeSec, state.durationSec)) player.setPaused(true) else player.setPaused(false)
    }
    val offer = askedResume && state.paused && resumeSec != null && !state.ended &&
        (state.durationSec <= 0 || shouldOfferResume(resumeSec, state.durationSec)) &&
        state.positionSec < 2

    LaunchedEffect(state.positionSec, state.paused, live) {
        if (!live && !state.paused && state.positionSec > 1) onSavePosition(state.positionSec)
    }
    LaunchedEffect(state.volume) { onSaveVolume(state.volume) }
    LaunchedEffect(controls, state.paused) {
        if (controls && !state.paused) {
            delay(3500)
            controls = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.pressed }) controls = true
                }
            }
        },
    ) {
        VideoSurface(player, Modifier.fillMaxSize())
        if (state.buffering) {
            Text(
                "Буферизация",
                color = P2160Colors.onSurface,
                modifier = Modifier.align(Alignment.Center).background(P2160Colors.scrim).padding(12.dp),
            )
        }
        state.error?.let {
            Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        if (controls || state.paused) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(P2160Colors.scrim).padding(12.dp)) {
                Text(title, color = P2160Colors.onSurface, maxLines = 1)
                if (live) {
                    Text("Эфир", color = P2160Colors.accent)
                } else {
                    val duration = state.durationSec
                    val shown = if (dragging) drag.toDouble() else state.positionSec
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatClock(shown), color = P2160Colors.onSurface)
                        Slider(
                            value = if (duration > 0) (shown / duration).toFloat().coerceIn(0f, 1f) else 0f,
                            onValueChange = {
                                dragging = true
                                drag = (it * duration).toFloat()
                                controls = true
                            },
                            onValueChangeFinished = {
                                if (duration > 0) player.seekTo(drag.toDouble())
                                dragging = false
                            },
                            enabled = duration > 0,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(formatRemaining(shown, duration), color = P2160Colors.onSurface)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccentButton(if (state.paused) "Играть" else "Пауза") { player.togglePause() }
                    AccentButton("Назад", onBack)
                    AccentButton(if (state.muted) "Звук выкл" else "Громкость ${state.volume}") { player.toggleMute() }
                    Slider(
                        value = state.volume / 100f,
                        onValueChange = { player.setVolume((it * 100).toInt()) },
                        modifier = Modifier.weight(1f),
                    )
                    AccentButton("Аудио") { showAudio = !showAudio; showSubs = false }
                    AccentButton("Субтитры") { showSubs = !showSubs; showAudio = false }
                }
            }
        }
        if (showAudio) TrackDialog("Звук", state, audio = true, player, onAddAudio) { showAudio = false }
        if (showSubs) TrackDialog("Субтитры", state, audio = false, player, onAddSubtitle) { showSubs = false }
        if (offer && resumeSec != null) {
            Column(
                Modifier.align(Alignment.Center).background(P2160Colors.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Продолжить с ${formatClock(resumeSec)}?", color = P2160Colors.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("Продолжить") {
                        player.seekTo(resumeSec)
                        player.setPaused(false)
                        askedResume = false
                    }
                    AccentButton("Сначала") {
                        player.setPaused(false)
                        askedResume = false
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackDialog(
    title: String,
    state: PlayState,
    audio: Boolean,
    player: MpvPlayer,
    onAddFile: () -> Unit,
    onClose: () -> Unit,
) {
    val tracks = if (audio) state.audio else state.subs
    Column(
        Modifier.fillMaxSize().background(P2160Colors.scrim).clickable { onClose() }.padding(48.dp),
    ) {
        Column(Modifier.background(P2160Colors.surface).padding(16.dp).clickable { }) {
            Text(title, color = P2160Colors.onSurface)
            ClickRow("Выкл", if (tracks.none { it.selected }) "выбрано" else "") {
                if (audio) player.selectAudio(null) else player.selectSub(null)
            }
            tracks.forEach { track ->
                ClickRow(track.title, if (track.selected) "выбрано" else "") {
                    if (audio) player.selectAudio(track.id) else player.selectSub(track.id)
                }
            }
            if (tracks.isEmpty()) Message("Дорожек в файле нет")
            AccentButton(if (audio) "Звук из файла" else "Субтитры из файла", onAddFile)
            AccentButton("Закрыть", onClose)
        }
    }
}
