package tv.p2160.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.p2160.core.i18n.tr
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbEntry
import tv.p2160.core.source.smb.SmbPath
import tv.p2160.core.source.smb.SmbServer
import java.util.Locale

val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "mov", "avi", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "vob",
    "flv", "3gp", "ogv", "rmvb", "divx", "asf", "f4v",
)
val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "ape", "dsf", "dff", "mka", "ac3", "dts", "thd")
const val ISO_EXTENSION = "iso"

enum class EntryKind { FOLDER, VIDEO, AUDIO, DISC, OTHER }

fun SmbEntry.kind(): EntryKind {
    if (isDirectory) return if (name.equals("BDMV", true)) EntryKind.DISC else EntryKind.FOLDER
    return when (name.substringAfterLast('.', "").lowercase()) {
        in VIDEO_EXTENSIONS -> EntryKind.VIDEO
        in AUDIO_EXTENSIONS -> EntryKind.AUDIO
        ISO_EXTENSION -> EntryKind.DISC
        else -> EntryKind.OTHER
    }
}

/**
 * Обзор папок SMB-сервера. [onPlay] получает все проигрываемые файлы папки и индекс выбранного —
 * так «Следующая серия» работает без отдельного плейлиста.
 */
@Composable
fun BrowserScreen(
    server: SmbServer,
    store: ResumeStore,
    onBack: () -> Unit,
    onPlay: (files: List<SmbEntry>, index: Int) -> Unit,
    onPlayDisc: (SmbPath) -> Unit,
) {
    val context = LocalContext.current
    val root = remember(server) { SmbPath(server.host, server.share, server.path) }
    var dir by remember(server) { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<SmbEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    // Позиция фокуса в каждой папке, чтобы после «назад» вернуться к той же строке.
    val lastFocus = remember { mutableStateMapOf<String, String>() }
    val changes by store.changes.collectAsState()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(dir, reload) {
        entries = null
        error = null
        withContext(Dispatchers.IO) {
            runCatching { SmbConnections.list(context, dir, server) }
        }.onSuccess { list ->
            entries = list.filter { it.kind() != EntryKind.OTHER }
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    fun goUp() {
        if (dir == root || dir.path.length <= root.path.length) onBack() else dir = dir.parent ?: root
    }
    BackHandler(onBack = ::goUp)

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = ::goUp, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(dir.name, style = MaterialTheme.typography.titleLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "\\\\${dir.host}\\${dir.share}\\${dir.path.replace('/', '\\')}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val list = entries
        when {
            error != null -> Column(Modifier.fillMaxWidth().padding(32.dp)) {
                Text(tr("browse.error", error.orEmpty()), color = colors.error)
                TextButton(onClick = { reload++ }) { Text(tr("player.retry")) }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(tr("browse.empty"), color = colors.onSurfaceVariant, modifier = Modifier.padding(32.dp))
            else -> {
                val playable = list.filter { it.kind() == EntryKind.VIDEO || it.kind() == EntryKind.AUDIO }
                val isDiscFolder = list.any { it.isDirectory && it.name.equals("BDMV", true) }
                val focusName = lastFocus[dir.path]
                val focusTarget = list.firstOrNull { it.name == focusName } ?: list.first()
                val requester = remember(dir, list) { FocusRequester() }
                LaunchedEffect(dir, list) { runCatching { requester.requestFocus() } }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = list.indexOf(focusTarget).coerceAtLeast(0))

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isDiscFolder) {
                        item(key = "disc") {
                            EntryRow(
                                icon = Icons.Default.Album, title = tr("browse.bluray_play"), subtitle = tr("browse.bluray"),
                                onClick = { onPlayDisc(dir) },
                            )
                        }
                    }
                    items(list, key = { it.path.path }) { entry ->
                        val kind = entry.kind()
                        val resume = remember(entry, changes) {
                            if (kind == EntryKind.VIDEO || kind == EntryKind.AUDIO) store.get(ResumeStore.keyFor(entry.path.toUri())) else null
                        }
                        EntryRow(
                            icon = when (kind) {
                                EntryKind.FOLDER -> Icons.Default.Folder
                                EntryKind.AUDIO -> Icons.Default.AudioFile
                                EntryKind.DISC -> Icons.Default.Album
                                else -> if (resume?.finished == true) Icons.Default.CheckCircle else Icons.Default.Movie
                            },
                            title = if (kind == EntryKind.FOLDER || kind == EntryKind.DISC && entry.isDirectory) entry.name else entry.name.substringBeforeLast('.'),
                            subtitle = when {
                                kind == EntryKind.DISC -> tr("browse.bluray")
                                entry.isDirectory -> null
                                else -> formatSize(entry.size)
                            },
                            progress = resume?.takeIf { !it.finished && it.positionMs > 0 }?.progress,
                            modifier = if (entry == focusTarget) Modifier.focusRequester(requester) else Modifier,
                            onClick = {
                                lastFocus[dir.path] = entry.name
                                when (kind) {
                                    EntryKind.FOLDER -> dir = entry.path
                                    EntryKind.DISC -> onPlayDisc(if (entry.isDirectory) dir else entry.path)
                                    else -> onPlay(playable, playable.indexOf(entry))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = colors.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
