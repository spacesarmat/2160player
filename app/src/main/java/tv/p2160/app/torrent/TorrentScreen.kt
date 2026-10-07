package tv.p2160.app.torrent

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.p2160.app.FocusCard
import tv.p2160.app.SectionTitle
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.Strings
import tv.p2160.core.i18n.tr
import tv.p2160.torrent.MagnetLink
import tv.p2160.torrent.NaturalOrder
import tv.p2160.torrent.TorrentEngine
import tv.p2160.torrent.TorrentFile
import tv.p2160.torrent.TorrentItem
import tv.p2160.torrent.TorrentPrefs
import tv.p2160.torrent.TorrentStats

/**
 * Экран торрентов: добавить magnet/.torrent, список торрентов, выбор файла → просмотр
 * во время загрузки. Единственная точка входа для навигации приложения.
 *
 * @param initialUri magnet: или content:// .torrent, пришедший извне (Intent) — добавляется сразу.
 * @param onPlay запрос для плеера (`Player2160.play(context, request)`).
 */
@Composable
fun TorrentScreen(
    onBack: () -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    initialUri: Uri? = null,
) {
    val context = LocalContext.current
    val engine = remember { TorrentEngine.install(context); TorrentEngine.get(context) }
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var magnetDialog by remember { mutableStateOf(false) }
    var settingsDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add(block: suspend () -> String) {
        busy = true
        error = null
        scope.launch {
            runCatching { block() }
                .onSuccess { openedId = it }
                .onFailure { error = strings.format("torrent.add_error", it.message ?: it.javaClass.simpleName) }
            busy = false
        }
    }

    val pickTorrent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) add { engine.addFromUri(uri) }
    }

    // Уже обработанный внешний URI — чтобы поворот экрана не добавлял его повторно.
    var handledUri by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialUri) {
        val uri = initialUri ?: return@LaunchedEffect
        if (handledUri == uri.toString()) return@LaunchedEffect
        handledUri = uri.toString()
        add { engine.addFromUri(uri) }
    }

    val id = openedId
    if (id != null) {
        BackHandler { openedId = null }
        TorrentDetails(engine, id, onBack = { openedId = null }, onPlay = onPlay)
    } else {
        TorrentList(
            engine = engine,
            busy = busy,
            error = error,
            onBack = onBack,
            onAddMagnet = { magnetDialog = true },
            onOpenFile = {
                try {
                    pickTorrent.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
                } catch (_: ActivityNotFoundException) {
                    error = strings["torrent.no_file_picker"]
                }
            },
            onSettings = { settingsDialog = true },
            onOpen = { openedId = it },
        )
    }

    if (magnetDialog) {
        MagnetDialog(
            onDismiss = { magnetDialog = false },
            onAdd = { text ->
                magnetDialog = false
                add { engine.addMagnet(text) }
            },
        )
    }
    if (settingsDialog) TorrentSettingsDialog(engine, onDismiss = { settingsDialog = false })
}

@Composable
private fun Header(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            title, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onBackground) {
    FocusCard(onClick = onClick, modifier = Modifier.size(48.dp), background = Color.Transparent) {
        Icon(icon, label, tint = tint, modifier = Modifier.align(Alignment.Center))
    }
}

// ---------------------------------------------------------------- список

@Composable
private fun TorrentList(
    engine: TorrentEngine,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onAddMagnet: () -> Unit,
    onOpenFile: () -> Unit,
    onSettings: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val torrents by engine.torrents.collectAsStateWithLifecycle()
    val engineError by engine.engineError.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<TorrentItem?>(null) }
    val strings = LocalStrings.current
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Header(tr("torrent.title"), onBack) { IconAction(Icons.Default.Settings, tr("torrent.settings"), onSettings) }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(Icons.Default.Link, tr("torrent.add_magnet"), onAddMagnet, Modifier.weight(1f).focusRequester(firstFocus))
                    Tile(Icons.Default.FileOpen, tr("torrent.open_file"), onOpenFile, Modifier.weight(1f))
                }
            }
            if (busy) item(key = "busy") { Progress(tr("torrent.adding")) }
            (error ?: engineError?.let { strings.format("torrent.engine_error", it) })?.let { msg ->
                item(key = "error") { Text(msg, color = colors.error, style = MaterialTheme.typography.bodyMedium) }
            }
            item(key = "title") { SectionTitle(tr("torrent.list")) }
            if (torrents.isEmpty()) {
                item(key = "empty") { Text(tr("torrent.empty"), color = colors.onSurfaceVariant) }
            }
            // Две колонки: раздачи парами, последняя без пары — на половину ширины.
            items(torrents.chunked(2), key = { it.first().id }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                    pair.forEach { t ->
                        TorrentRow(t, onClick = { onOpen(t.id) }, onRemove = { removing = t }, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item(key = "legal") {
                Text(tr("torrent.legal"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
    removing?.let { t -> RemoveDialog(engine, t, onDismiss = { removing = null }, onRemoved = { removing = null }) }
}

@Composable
private fun Tile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.height(76.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TorrentRow(t: TorrentItem, onClick: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    FocusCard(onClick = onClick, onLongClick = onRemove, modifier = modifier) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.name, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(statusLine(strings, t), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                val s = t.stats
                if (s != null && s.primaryFile >= 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { s.primaryProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.width(8.dp))
            IconAction(Icons.Default.Delete, tr("torrent.remove"), onRemove, tint = colors.onSurfaceVariant)
        }
    }
}

private fun statusLine(s: Strings, t: TorrentItem): String {
    val st = t.stats ?: return if (t.stored.hasData) {
        s["torrent.state_stored"] + " · " + formatSize(s, t.stored.bytesDone) + " / " + formatSize(s, t.stored.totalSize)
    } else s["torrent.state_stored"]
    val state = when {
        st.state == TorrentStats.State.ERROR -> s.format("torrent.state_error", st.error)
        st.state == TorrentStats.State.METADATA -> s["torrent.state_metadata"]
        st.state == TorrentStats.State.CHECKING -> s["torrent.state_checking"]
        st.paused -> s["torrent.state_paused"]
        st.state == TorrentStats.State.FINISHED -> s["torrent.state_finished"]
        else -> s["torrent.state_downloading"]
    }
    val parts = mutableListOf(state)
    if (!st.paused && st.state != TorrentStats.State.ERROR) parts += speedLine(s, st)
    if (st.primaryFile >= 0) parts += "${(st.primaryProgress * 100).toInt()}%"
    return parts.joinToString(" · ")
}

private fun speedLine(s: Strings, st: TorrentStats): String =
    s.format("torrent.speed", formatSize(s, st.downloadRate.toLong()), formatSize(s, st.uploadRate.toLong()), st.peers)

internal fun formatSize(s: Strings, bytes: Long): String {
    val kb = 1024.0
    return when {
        bytes >= kb * kb * kb -> String.format(s.locale, "%.1f %s", bytes / (kb * kb * kb), s["torrent.unit_gb"])
        bytes >= kb * kb -> String.format(s.locale, "%.1f %s", bytes / (kb * kb), s["torrent.unit_mb"])
        else -> String.format(s.locale, "%d %s", (bytes / kb).toLong(), s["torrent.unit_kb"])
    }
}

// ---------------------------------------------------------------- файлы торрента

@Composable
private fun TorrentDetails(engine: TorrentEngine, id: String, onBack: () -> Unit, onPlay: (PlaybackRequest) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val torrents by engine.torrents.collectAsStateWithLifecycle()
    val item = torrents.firstOrNull { it.id == id }
    var files by remember(id) { mutableStateOf(engine.files(id)) }
    var waitError by remember(id) { mutableStateOf<String?>(null) }
    var started by remember(id) { mutableLongStateOf(System.currentTimeMillis()) }
    var elapsed by remember(id) { mutableLongStateOf(0L) }
    var preparing by remember { mutableStateOf<Int?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    // Метаданные: ждём, пока не придут (экран можно закрыть в любой момент).
    LaunchedEffect(id) {
        started = System.currentTimeMillis()
        runCatching {
            while (files == null) files = engine.awaitFiles(id, 5_000)
        }.onFailure { waitError = it.message ?: it.javaClass.simpleName }
    }
    LaunchedEffect(id, files == null) {
        while (files == null) {
            elapsed = System.currentTimeMillis() - started
            delay(1_000)
        }
    }
    LaunchedEffect(files != null) { if (files != null) runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Header(item?.name ?: id, onBack) {
            IconAction(Icons.Default.Delete, tr("torrent.remove"), { removing = true })
        }
        val list = files
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "stats") {
                item?.stats?.let { st ->
                    Text(speedLine(strings, st), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
            when {
                waitError != null -> item(key = "err") { Text(tr("torrent.add_error", waitError), color = colors.error) }
                list == null -> item(key = "meta") {
                    Column {
                        Progress(tr("torrent.metadata"))
                        val peers = item?.stats?.peers ?: 0
                        Text(
                            tr("torrent.metadata_stats", formatElapsed(elapsed), peers, engine.dhtNodes()),
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                        if (elapsed > 60_000) Text(tr("torrent.metadata_slow"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    val ordered = displayOrder(list)
                    if (ordered.none { it.isPlayable }) item(key = "novideo") { Text(tr("torrent.no_video"), color = colors.onSurfaceVariant) }
                    item(key = "files") { SectionTitle(tr("torrent.files")) }
                    playError?.let { msg -> item(key = "playerr") { Text(msg, color = colors.error) } }
                    items(ordered, key = { "f" + it.index }) { f ->
                        val isPrimary = item?.stats?.primaryFile == f.index
                        FileRow(
                            file = f,
                            progress = if (isPrimary) item?.stats?.primaryProgress else null,
                            busy = preparing == f.index,
                            modifier = if (f == ordered.first()) Modifier.focusRequester(firstFocus) else Modifier,
                            onClick = {
                                if (!f.isPlayable || preparing != null) return@FileRow
                                preparing = f.index
                                playError = null
                                scope.launch {
                                    runCatching { engine.prepare(id, f.index) }
                                        .onSuccess(onPlay)
                                        .onFailure { playError = strings.format("torrent.play_error", it.message ?: it.javaClass.simpleName) }
                                    preparing = null
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (removing && item != null) RemoveDialog(engine, item, onDismiss = { removing = false }, onRemoved = { removing = false; onBack() })
}

/** Видео (естественный порядок), затем аудио, субтитры и прочее. */
private fun displayOrder(files: List<TorrentFile>): List<TorrentFile> {
    fun rank(f: TorrentFile) = when {
        f.isVideo -> 0
        f.isAudio -> 1
        f.isSubtitle -> 2
        else -> 3
    }
    return files.sortedWith(compareBy<TorrentFile> { rank(it) }.then(NaturalOrder))
}

@Composable
private fun FileRow(file: TorrentFile, progress: Float?, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val icon = when {
        file.isVideo -> Icons.Default.Movie
        file.isAudio -> Icons.Default.AudioFile
        file.isSubtitle -> Icons.Default.Subtitles
        else -> Icons.Default.Description
    }
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (file.isPlayable) colors.primary else colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (file.isPlayable) colors.onSurface else colors.onSurfaceVariant,
                )
                val folder = file.path.replace('\\', '/').substringBeforeLast('/', "").substringAfter('/', "")
                Text(
                    listOfNotNull(formatSize(strings, file.size), folder.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (busy) {
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------- диалоги

@Composable
private fun MagnetDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val context = LocalContext.current
    fun clipboard(): String? = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }.getOrNull()

    // Если в буфере уже magnet — подставляем сразу.
    var text by remember { mutableStateOf(clipboard()?.takeIf(MagnetLink::looksLikeMagnet)?.trim().orEmpty()) }
    val valid = MagnetLink.looksLikeMagnet(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("torrent.add_magnet")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(tr("torrent.add_magnet_hint")) },
                    placeholder = { Text("magnet:?xt=urn:btih:…") },
                    isError = text.isNotBlank() && !valid,
                    supportingText = { if (text.isNotBlank() && !valid) Text(tr("torrent.invalid_magnet")) },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { clipboard()?.let { text = it.trim() } }) { Text(tr("torrent.paste")) }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text.trim()) }, enabled = valid) { Text(tr("torrent.add")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}

@Composable
private fun RemoveDialog(engine: TorrentEngine, item: TorrentItem, onDismiss: () -> Unit, onRemoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun remove(deleteFiles: Boolean) {
        scope.launch {
            runCatching { engine.remove(item.id, deleteFiles) }
            onRemoved()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("torrent.remove_title")) },
        text = { Text(item.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { remove(true) }) { Text(tr("torrent.remove_files"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { remove(false) }) { Text(tr("torrent.remove_keep")) }
                TextButton(onClick = onDismiss) { Text(tr("app.cancel")) }
            }
        },
    )
}

@Composable
private fun TorrentSettingsDialog(engine: TorrentEngine, onDismiss: () -> Unit) {
    val prefs by engine.settings.state.collectAsStateWithLifecycle()
    val strings = LocalStrings.current
    fun <T> next(list: List<T>, current: T): T = list[(list.indexOf(current) + 1).mod(list.size)]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("torrent.settings")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    SettingRow(
                        tr("torrent.cache_limit"),
                        if (prefs.cacheLimitGb == 0) tr("torrent.unlimited") else tr("torrent.gb", prefs.cacheLimitGb),
                    ) { engine.settings.update { it.copy(cacheLimitGb = next(TorrentPrefs.CACHE_LIMITS, it.cacheLimitGb)) } }
                }
                item {
                    SettingRow(
                        tr("torrent.keep_files"),
                        tr(if (prefs.keepFiles) "torrent.on" else "torrent.off"),
                        hint = tr("torrent.keep_files_hint"),
                    ) { engine.settings.update { it.copy(keepFiles = !it.keepFiles) } }
                }
                item {
                    SettingRow(tr("torrent.max_age"), tr("torrent.days", prefs.maxAgeDays)) {
                        engine.settings.update { it.copy(maxAgeDays = next(TorrentPrefs.MAX_AGE_DAYS, it.maxAgeDays)) }
                    }
                }
                item {
                    SettingRow(tr("torrent.max_connections"), prefs.maxConnections.toString()) {
                        engine.settings.update { it.copy(maxConnections = next(TorrentPrefs.CONNECTIONS, it.maxConnections)) }
                    }
                }
                item {
                    SettingRow(
                        tr("torrent.upload_limit"),
                        if (prefs.uploadLimitKb == 0) tr("torrent.unlimited") else strings.format("torrent.kbps", prefs.uploadLimitKb),
                    ) { engine.settings.update { it.copy(uploadLimitKb = next(TorrentPrefs.UPLOAD_LIMITS, it.uploadLimitKb)) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("torrent.close")) } },
    )
}

/** Строка настройки: нажатие (пульт или палец) переключает на следующее значение. */
@Composable
private fun SettingRow(title: String, value: String, hint: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), background = colors.surfaceVariant) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                hint?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            Spacer(Modifier.width(12.dp))
            Text(value, color = colors.primary, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
