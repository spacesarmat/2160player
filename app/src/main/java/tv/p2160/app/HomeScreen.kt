package tv.p2160.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tv.p2160.core.i18n.tr
import tv.p2160.app.handoff.Handoff
import tv.p2160.app.handoff.RemoteSession
import androidx.compose.material.icons.filled.Devices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.p2160.core.resume.ResumeEntry
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.ui.formatTime

@Composable
fun HomeScreen(
    store: ResumeStore,
    onOpenFile: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenIptv: () -> Unit,
    onOpenTorrents: () -> Unit,
    onPlayEntry: (ResumeEntry) -> Unit,
    onPlayRemote: (RemoteSession) -> Unit,
) {
    // Что играет на других устройствах в сети — опрашиваем раз в несколько секунд.
    val peers by Handoff.peers.collectAsState()
    var remote by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }
    LaunchedEffect(peers) {
        while (true) {
            remote = withContext(Dispatchers.IO) {
                peers.mapNotNull(Handoff::fetchSession).filter { it.durationMs > 0 && it.positionMs > 5_000 }
            }
            kotlinx.coroutines.delay(5_000)
        }
    }
    val changes by store.changes.collectAsState()
    val history = remember(changes) { store.recent(100) }
    val continueList = history.filter { !it.finished && it.positionMs > 0 }
    var urlDialog by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(horizontal = if (LocalConfiguration.current.screenWidthDp >= 600) 32.dp else 16.dp, vertical = 20.dp),
    ) {
        item(key = "header") {
            Image(
                painter = painterResource(if (colors.background.luminance() > 0.5f) R.drawable.logo_header_light_small else R.drawable.logo_header_small),
                contentDescription = "2160 Player",
                modifier = Modifier.height(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            // Компактная сетка: 3 колонки на телефоне в портрете, все 6 в ряд на широком экране/ТВ.
            val actions = listOf(
                Triple(Icons.Default.FolderOpen, tr("app.open_file"), onOpenFile),
                Triple(Icons.Default.Lan, tr("app.network"), onOpenNetwork),
                Triple(Icons.Default.LiveTv, tr("iptv.title"), onOpenIptv),
                Triple(Icons.Default.Download, tr("torrent.title"), onOpenTorrents),
                Triple(Icons.Default.Link, tr("app.link"), { urlDialog = true }),
                Triple(Icons.Default.Settings, tr("app.settings"), onOpenSettings),
            )
            val columns = if (LocalConfiguration.current.screenWidthDp >= 600) actions.size else 3
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.chunked(columns).forEachIndexed { row, chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        chunk.forEachIndexed { i, (icon, label, onClick) ->
                            val focus = if (row == 0 && i == 0) Modifier.focusRequester(firstFocus) else Modifier
                            ActionTile(icon, label, onClick, Modifier.weight(1f).then(focus))
                        }
                        repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        if (remote.isNotEmpty()) {
            item(key = "remote") {
                SectionTitle(tr("handoff.other_devices"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(remote, key = { "r" + it.peer.id }) { session ->
                        FocusCard(onClick = { onPlayRemote(session) }, modifier = Modifier.width(240.dp).height(116.dp)) {
                            Column(Modifier.fillMaxSize().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Devices, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(session.peer.name, color = colors.primary, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(session.title, color = colors.onSurface, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(tr("handoff.continue_from", formatTime(session.positionMs)), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Movie, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(tr("app.empty_title"), style = MaterialTheme.typography.titleLarge, color = colors.onBackground)
                    Text(tr("app.empty_hint"), color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }

        if (continueList.isNotEmpty()) {
            item(key = "continue") {
                SectionTitle(tr("app.continue"))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(continueList, key = { "c" + it.key }) { entry ->
                        ContinueCard(entry, onClick = { onPlayEntry(entry) }, onLongClick = { store.delete(entry.key) })
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item(key = "history-title") { SectionTitle(tr("app.history")) }
            items(history, key = { "h" + it.key }) { entry ->
                HistoryRow(entry, onClick = { onPlayEntry(entry) }, onLongClick = { store.delete(entry.key) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (urlDialog) {
        UrlDialog(onDismiss = { urlDialog = false }, onPlay = { urlDialog = false; onOpenUrl(it) })
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusCard(onClick = onClick, modifier = modifier.height(76.dp)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ContinueCard(entry: ResumeEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.width(240.dp).height(116.dp)) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Text(
                entry.title ?: entry.uri,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                tr("app.remaining", formatTime(entry.durationMs - entry.positionMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
            )
        }
    }
}

@Composable
private fun HistoryRow(entry: ResumeEntry, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(colors.primary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (entry.finished) Icons.Default.CheckCircle else Icons.Default.Movie, null,
                    tint = colors.primary, modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.title ?: entry.uri, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        entry.finished -> tr("app.watched")
                        entry.durationMs > 0 -> "${formatTime(entry.positionMs)} / ${formatTime(entry.durationMs)}"
                        else -> entry.uri
                    },
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(clipboardUrl(context).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("app.open_url")) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                singleLine = true,
                placeholder = { Text(tr("app.url_hint")) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onPlay(url) }, enabled = url.contains("://")) { Text(tr("app.play")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}

private fun clipboardUrl(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val text = runCatching { clipboard.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull()?.trim()
    return text?.takeIf { it.contains("://") && !it.contains(' ') }
}
