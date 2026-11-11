package tv.p2160.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import tv.p2160.desktop.media.isMediaFile
import tv.p2160.desktop.player.MpvPlayer
import tv.p2160.desktop.ui.DlnaScreen
import tv.p2160.desktop.ui.HomeScreen
import tv.p2160.desktop.ui.IptvScreen
import tv.p2160.desktop.ui.P2160Colors
import tv.p2160.desktop.ui.P2160Theme
import tv.p2160.desktop.ui.PlayerScreen
import tv.p2160.desktop.ui.SmbScreen
import tv.p2160.desktop.ui.TorrentScreen
import java.awt.FileDialog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--dump-frame") {
        val out = File(args.getOrNull(1) ?: error("usage: --dump-frame out.png media"))
        val media = args.getOrNull(2) ?: error("usage: --dump-frame out.png media")
        MpvPlayer.dumpFirstFrame(media, out)
        return
    }
    val initial = args.firstOrNull { !it.startsWith("-") }
    application {
        val app = remember {
            val library = tv.p2160.desktop.media.LibraryStore()
            val started = runCatching { MpvPlayer.start() }
            DesktopController(library, started.getOrNull(), started.exceptionOrNull()?.message)
        }
        DisposableEffect(app) { onDispose { app.close() } }
        var fullscreen by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(width = 1100.dp, height = 720.dp)
        LaunchedEffect(fullscreen) {
            windowState.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        LaunchedEffect(initial) {
            if (initial != null) app.openLocation(initial)
        }
        Window(
            onCloseRequest = ::exitApplication,
            title = "2160 Player",
            state = windowState,
            onPreviewKeyEvent = { event ->
                if (event.type != KeyEventType.KeyDown || app.typing) return@Window false
                handleKey(event.key, event.isCtrlPressed, app, fullscreen) { fullscreen = it }
            },
        ) {
            val window = this.window
            DisposableEffect(window) {
                installDrop(window, app)
                onDispose { window.dropTarget = null }
            }
            P2160Theme {
                Box(Modifier.fillMaxSize().background(P2160Colors.background)) {
                    when (val route = app.route) {
                        Route.Home -> HomeScreen(
                            recent = app.library.recent(),
                            playerError = app.playerError,
                            onOpenFile = { pickFile(window, media = true)?.let(app::openFile) },
                            onOpenUrl = { app.openUrl(it) },
                            onSmb = app::openSmb,
                            onDlna = app::openDlna,
                            onIptv = app::openIptv,
                            onTorrent = app::openTorrent,
                            onRecent = app::openRecent,
                            onTyping = { app.typing = it },
                        )
                        Route.Smb -> SmbScreen(
                            saved = app.library.servers(),
                            entries = app.smbEntries,
                            shares = app.smbShares,
                            pathLabel = app.smbLabel,
                            error = app.smbError,
                            busy = app.smbBusy,
                            onBack = app::back,
                            onConnect = app::connectSmb,
                            onShare = app::openShare,
                            onEntry = app::openSmbEntry,
                            onUp = app::smbUp,
                            onTyping = { app.typing = it },
                        )
                        Route.Dlna -> DlnaScreen(
                            servers = app.dlnaServers,
                            crumbs = app.dlnaCrumbs,
                            objects = app.dlnaObjects,
                            error = app.dlnaError,
                            busy = app.dlnaBusy,
                            onBack = app::back,
                            onSearch = app::searchDlna,
                            onAddress = app::probeDlna,
                            onServer = app::openDlnaServer,
                            onObject = app::openDlnaObject,
                            onUp = app::dlnaUp,
                            onTyping = { app.typing = it },
                        )
                        Route.Iptv -> IptvScreen(
                            channels = app.iptvChannels,
                            error = app.iptvError,
                            busy = app.iptvBusy,
                            onBack = app::back,
                            onOpenFile = { pickFile(window, media = false)?.let(app::openIptvFile) },
                            onOpenUrl = app::openIptvUrl,
                            onChannel = app::playChannel,
                            onTyping = { app.typing = it },
                        )
                        Route.Torrent -> TorrentScreen(
                            rows = app.torrentRows,
                            error = app.torrentError,
                            onBack = app::back,
                            onMagnet = app::addMagnet,
                            onPickFile = { pickFile(window, media = false)?.let(app::addTorrentFile) },
                            onPlay = { id, file -> app.playTorrent(id, file) },
                            onRemove = app::removeTorrent,
                            onTyping = { app.typing = it },
                        )
                        is Route.Player -> {
                            val engine = app.player
                            if (engine == null) {
                                HomeScreen(
                                    recent = app.library.recent(),
                                    playerError = app.playerError,
                                    onOpenFile = {},
                                    onOpenUrl = {},
                                    onSmb = {},
                                    onDlna = {},
                                    onIptv = {},
                                    onTorrent = {},
                                    onRecent = {},
                                    onTyping = {},
                                )
                            } else {
                                key(route.request.key) {
                                    PlayerScreen(
                                        player = engine,
                                        title = route.request.title,
                                        resumeSec = route.request.resumeSec,
                                        live = route.request.live,
                                        onBack = app::back,
                                        onSavePosition = { app.savePosition(route.request, it) },
                                        onSaveVolume = app::noteVolume,
                                        onAddSubtitle = {
                                            pickFile(window, media = false)?.let { engine.addSubtitle(it.absolutePath) }
                                        },
                                        onAddAudio = {
                                            pickFile(window, media = false)?.let { engine.addAudio(it.absolutePath) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DesktopController.openLocation(raw: String) {
    val file = File(raw)
    if (file.isFile) openFile(file) else openUrl(raw)
}

private fun handleKey(
    key: Key,
    ctrl: Boolean,
    app: DesktopController,
    fullscreen: Boolean,
    setFullscreen: (Boolean) -> Unit,
): Boolean {
    val player = app.player
    val playing = app.route is Route.Player && player != null
    return when (key) {
        Key.Spacebar, Key.K -> {
            if (playing) player.togglePause()
            playing
        }
        Key.DirectionLeft -> {
            if (playing && app.route is Route.Player && !app.route.let { (it as Route.Player).request.live }) player.seekBy(-10.0)
            playing
        }
        Key.DirectionRight -> {
            if (playing && app.route is Route.Player && !(app.route as Route.Player).request.live) player.seekBy(10.0)
            playing
        }
        Key.DirectionUp -> {
            if (playing) player.setVolume(player.state.value.volume + 5)
            playing
        }
        Key.DirectionDown -> {
            if (playing) player.setVolume(player.state.value.volume - 5)
            playing
        }
        Key.F, Key.F11 -> {
            if (playing) setFullscreen(!fullscreen)
            playing
        }
        Key.M -> {
            if (playing) player.toggleMute()
            playing
        }
        Key.Escape -> when {
            fullscreen -> {
                setFullscreen(false)
                true
            }
            app.stack.size > 1 -> {
                app.back()
                true
            }
            else -> false
        }
        Key.O -> if (ctrl || !playing) {
            false
        } else false
        else -> false
    }
}

private fun pickFile(window: ComposeWindow, media: Boolean): File? {
    val dialog = FileDialog(window, "Открыть", FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val file = File(dialog.directory, name)
    if (media && !file.isMediaFile() && file.extension.lowercase() !in setOf("m3u", "m3u8", "torrent")) {
        // Любой выбранный файл всё равно открываем: контейнер может быть редким.
    }
    return file
}

private fun installDrop(window: ComposeWindow, app: DesktopController): DropTarget {
    val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull().orEmpty()
            val file = files.firstOrNull() ?: return
            val playing = app.route is Route.Player
            val ext = file.extension.lowercase()
            when {
                playing && ext in tv.p2160.desktop.media.SUBTITLE_EXTENSIONS -> app.player?.addSubtitle(file.absolutePath)
                playing && ext in tv.p2160.desktop.media.AUDIO_EXTENSIONS -> app.player?.addAudio(file.absolutePath)
                else -> app.openFile(file)
            }
            event.dropComplete(true)
        }
    })
    return target
}
