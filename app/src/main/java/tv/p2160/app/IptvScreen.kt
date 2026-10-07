package tv.p2160.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.Strings
import tv.p2160.core.i18n.tr
import tv.p2160.core.iptv.EpgGuide
import tv.p2160.core.iptv.EpgProgramme
import tv.p2160.core.iptv.IptvChannel
import tv.p2160.core.iptv.IptvPlaylist
import tv.p2160.core.iptv.IptvStore
import java.text.SimpleDateFormat
import java.util.Date

/**
 * IPTV: список плейлистов → каналы (группы, поиск, избранное, «сейчас/далее» из EPG).
 * Единственная точка входа для навигации приложения; [onPlay] получает готовый запрос
 * с `liveTv = true` — передайте его в `Player2160.play`.
 */
@Composable
fun IptvScreen(onBack: () -> Unit, onPlay: (PlaybackRequest) -> Unit) {
    val context = LocalContext.current
    val store = remember { IptvStore.get(context) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val playlists by store.playlists.collectAsStateWithLifecycle()
    val open = openId?.let { id -> playlists.firstOrNull { it.id == id } }
    if (open == null) {
        PlaylistsPage(store, playlists, onBack = onBack, onOpen = { openId = it })
    } else {
        ChannelsPage(store, open, onBack = { openId = null }, onPlay = onPlay)
    }
}

// region Плейлисты

@Composable
private fun PlaylistsPage(store: IptvStore, playlists: List<IptvPlaylist>, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<IptvPlaylist?>(null) }
    var adding by remember { mutableStateOf(false) }
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    BackHandler(onBack = onBack)

    fun refresh(p: IptvPlaylist) {
        busy[p.id] = true
        scope.launch {
            runCatching { store.channels(p.id, forceRefresh = true) }
            runCatching { store.guide(p.id, forceRefresh = true) }
            busy.remove(p.id)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Header(title = tr("iptv.title"), onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(playlists, key = { it.id }) { p ->
                val err = p.error
                val status = when {
                    err != null -> describeError(err, strings)
                    p.updatedAt > 0 -> strings.format("iptv.channels_count", p.channelCount) + " · " +
                        strings.format("iptv.updated", formatDate(p.updatedAt, strings))
                    else -> strings["iptv.not_loaded"]
                }
                IptvRow(
                    icon = Icons.Default.LiveTv,
                    title = p.name.ifBlank { p.source },
                    subtitle = status,
                    subtitleColor = if (p.error != null) colors.error else colors.onSurfaceVariant,
                    onClick = { onOpen(p.id) },
                    onLongClick = { editing = p },
                    modifier = if (p == playlists.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    trailing = {
                        if (busy[p.id] == true) {
                            CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), strokeWidth = 2.dp)
                        } else {
                            IconCard(Icons.Default.Refresh, tr("iptv.refresh")) { refresh(p) }
                        }
                        IconCard(Icons.Default.Edit, tr("iptv.edit")) { editing = p }
                    },
                )
            }
            item(key = "add") {
                IptvRow(
                    icon = Icons.Default.Add,
                    title = tr("iptv.add"),
                    subtitle = tr("iptv.add_hint"),
                    onClick = { adding = true },
                    modifier = if (playlists.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            if (playlists.isEmpty()) {
                item(key = "empty") {
                    Text(tr("iptv.empty"), color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp).widthIn(max = 640.dp))
                }
            }
        }
    }

    if (adding) {
        PlaylistDialog(initial = null, store = store, onDismiss = { adding = false }, onSaved = { adding = false; onOpen(it.id) })
    }
    editing?.let { p ->
        PlaylistDialog(initial = p, store = store, onDismiss = { editing = null }, onSaved = { editing = null })
    }
}

@Composable
private fun PlaylistDialog(initial: IptvPlaylist?, store: IptvStore, onDismiss: () -> Unit, onSaved: (IptvPlaylist) -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var source by remember { mutableStateOf(initial?.source.orEmpty()) }
    var epg by remember { mutableStateOf(initial?.epgUrl.orEmpty()) }
    var userAgent by remember { mutableStateOf(initial?.userAgent.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // Доступ к файлу нужен и после перезапуска — для обновления плейлиста.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        source = uri.toString()
        if (name.isBlank()) name = displayName(context, uri)?.substringBeforeLast('.').orEmpty()
        error = null
    }
    val isFile = source.startsWith("content:", true) || source.startsWith("file:", true)

    fun save() {
        val src = source.trim()
        if (src.isEmpty()) return
        val playlist = (initial ?: IptvPlaylist(name = "", source = src)).copy(
            name = name.trim().ifEmpty { Uri.parse(src).host ?: src.substringAfterLast('/').substringBeforeLast('.') },
            source = src,
            epgUrl = epg.trim().ifEmpty { null },
            userAgent = userAgent.trim().ifEmpty { null },
        )
        store.save(playlist)
        onSaved(playlist)
    }

    if (confirmDelete && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(tr("iptv.delete_confirm", initial.name)) },
            confirmButton = {
                TextButton(onClick = { store.delete(initial.id); confirmDelete = false; onDismiss() }) {
                    Text(tr("iptv.delete"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("app.cancel")) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "iptv.add" else "iptv.edit")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        if (isFile) strings.format("iptv.file_selected", displayName(context, Uri.parse(source)) ?: source) else source,
                        { source = it; error = null },
                        label = { Text(tr("iptv.url")) },
                        placeholder = { Text("http://") },
                        supportingText = { Text(tr("iptv.url_hint")) },
                        singleLine = true,
                        readOnly = isFile,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        trailingIcon = if (isFile) {
                            { IconCard(Icons.Default.Close, tr("app.cancel")) { source = "" } }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    TextButton(onClick = {
                        try {
                            pickFile.launch(arrayOf("audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "application/octet-stream", "*/*"))
                        } catch (_: ActivityNotFoundException) {
                            error = strings["app.no_file_picker"]
                        }
                    }) { Text(tr("iptv.pick_file")) }
                }
                item { OutlinedTextField(name, { name = it }, label = { Text(tr("iptv.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        epg, { epg = it }, label = { Text(tr("iptv.epg_url")) }, supportingText = { Text(tr("iptv.epg_hint")) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        userAgent, { userAgent = it }, label = { Text(tr("iptv.user_agent")) }, supportingText = { Text(tr("iptv.user_agent_hint")) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = { TextButton(onClick = ::save, enabled = source.isNotBlank()) { Text(tr("iptv.save")) } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { confirmDelete = true }) { Text(tr("iptv.delete"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(tr("app.cancel")) }
            }
        },
    )
}

// endregion

// region Каналы

private const val GROUP_ALL = "\u0000all"
private const val GROUP_FAV = "\u0000fav"

@Composable
private fun ChannelsPage(store: IptvStore, playlist: IptvPlaylist, onBack: () -> Unit, onPlay: (PlaybackRequest) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val id = playlist.id

    var parsed by remember(id) { mutableStateOf(store.cachedChannels(id)) }
    var loading by remember(id) { mutableStateOf(false) }
    var loadError by remember(id) { mutableStateOf<String?>(null) }
    var reload by remember(id) { mutableStateOf(0 to false) }   // (счётчик, принудительно)
    var guide by remember(id) { mutableStateOf(store.cachedGuide(id)) }
    var guideLoading by remember(id) { mutableStateOf(false) }
    val guideVersion by store.guideVersion.collectAsStateWithLifecycle()
    val favourites = store.favourites.collectAsStateWithLifecycle().value[id].orEmpty()

    var group by rememberSaveable(id) { mutableStateOf(GROUP_ALL) }
    var query by rememberSaveable(id) { mutableStateOf("") }
    var searching by rememberSaveable(id) { mutableStateOf(false) }
    val lastChanges by store.lastChannelChanges.collectAsStateWithLifecycle()
    val lastChannelId = remember(id, lastChanges) { store.lastChannel(id) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }

    LaunchedEffect(id, reload) {
        loading = true
        loadError = null
        runCatching { store.channels(id, forceRefresh = reload.second) }
            .onSuccess { parsed = it }
            .onFailure { loadError = describeError(it.message ?: it.javaClass.simpleName, strings) }
        loading = false
        // Телепрограмма — после каналов, в фоне.
        if (parsed != null) {
            guideLoading = guide == null
            runCatching { store.guide(id, forceRefresh = reload.second) }.onSuccess { if (it != null) guide = it }
            guideLoading = false
        }
    }
    LaunchedEffect(guideVersion) { store.cachedGuide(id)?.let { guide = it } }

    // Передачи по id канала — считаем один раз на телепрограмму (сопоставление по имени не бесплатное).
    val schedule by produceState<Map<String, List<EpgProgramme>>>(emptyMap(), guide, parsed) {
        val g = guide
        val p = parsed
        value = if (g == null || p == null) emptyMap()
        else withContext(Dispatchers.Default) { p.channels.associate { it.id to g.programmes(it) }.filterValues { it.isNotEmpty() } }
    }

    val all = parsed?.channels.orEmpty()
    val groups = remember(parsed) { parsed?.groups.orEmpty() }
    val visible = remember(parsed, group, query, favourites) {
        val base = when (group) {
            GROUP_ALL -> all
            GROUP_FAV -> all.filter { it.id in favourites }
            else -> all.filter { group in it.groups }
        }
        val q = query.trim()
        if (q.isEmpty()) base else base.filter { it.name.contains(q, ignoreCase = true) || it.tvgName?.contains(q, ignoreCase = true) == true }
    }

    BackHandler {
        when {
            searching -> { searching = false; query = "" }
            else -> onBack()
        }
    }

    fun play(index: Int) {
        if (index !in visible.indices) return
        onPlay(store.playbackRequest(id, visible, index))
    }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Шапка: назад, название, поиск, обновить.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            if (searching) {
                val searchFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                OutlinedTextField(
                    query, { query = it },
                    placeholder = { Text(tr("iptv.search")) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus)
                        // На пульте стрелки вверх/вниз уводят фокус из поля, а не двигают курсор.
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                else -> false
                            }
                        },
                )
                Spacer(Modifier.width(8.dp))
                IconCard(Icons.Default.Close, tr("app.cancel")) { searching = false; query = "" }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = when {
                        loading -> strings["iptv.loading"]
                        guideLoading -> strings["iptv.epg_loading"]
                        parsed != null -> strings.format("iptv.channels_count", all.size)
                        else -> ""
                    }
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconCard(Icons.Default.Search, tr("iptv.search")) { searching = true }
            }
            Spacer(Modifier.width(4.dp))
            if (loading) CircularProgressIndicator(Modifier.padding(10.dp).size(24.dp), strokeWidth = 2.dp)
            else IconCard(Icons.Default.Refresh, tr("iptv.refresh")) { reload = (reload.first + 1) to true }
        }

        // Группы.
        if (parsed != null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = GROUP_FAV) { GroupChip(strings["iptv.favourites"], group == GROUP_FAV, Icons.Default.Star) { group = GROUP_FAV } }
                item(key = GROUP_ALL) { GroupChip(strings["iptv.all"], group == GROUP_ALL) { group = GROUP_ALL } }
                items(groups, key = { "g:$it" }) { g -> GroupChip(g, group == g) { group = g } }
            }
        }

        val err = loadError
        when {
            parsed == null && err != null -> Column(Modifier.padding(32.dp)) {
                Text(err, color = colors.error)
                TextButton(onClick = { reload = (reload.first + 1) to true }) { Text(tr("player.retry")) }
            }
            parsed == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            visible.isEmpty() -> Text(
                tr(if (group == GROUP_FAV && query.isBlank()) "iptv.no_favourites" else "iptv.no_channels"),
                color = colors.onSurfaceVariant, modifier = Modifier.padding(32.dp).widthIn(max = 640.dp),
            )
            else -> key("$id|$group|$query") { ChannelList(
                channels = visible,
                listKey = "$id|$group|$query",
                lastChannelId = lastChannelId,
                favourites = favourites,
                schedule = schedule,
                now = now,
                onPlay = ::play,
                onToggleFavourite = { store.toggleFavourite(id, it.id) },
                takeFocus = !searching,
            ) }
        }
    }
}

@Composable
private fun ChannelList(
    channels: List<IptvChannel>,
    listKey: String,
    lastChannelId: String?,
    favourites: Set<String>,
    schedule: Map<String, List<EpgProgramme>>,
    now: Long,
    onPlay: (Int) -> Unit,
    onToggleFavourite: (IptvChannel) -> Unit,
    takeFocus: Boolean,
) {
    val lastIndex = channels.indexOfFirst { it.id == lastChannelId }
    val focusIndex = lastIndex.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (focusIndex - 2).coerceAtLeast(0))
    val requester = remember(listKey) { FocusRequester() }
    // Фокус на последний просмотренный канал (или первый) — пульт сразу «в списке».
    LaunchedEffect(listKey, takeFocus) {
        if (!takeFocus) return@LaunchedEffect
        if (state.layoutInfo.visibleItemsInfo.none { it.index == focusIndex }) state.scrollToItem((focusIndex - 2).coerceAtLeast(0))
        delay(50)
        runCatching { requester.requestFocus() }
    }
    val logoPx = with(LocalDensity.current) { 64.dp.roundToPx() }

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
            val programmes = schedule[channel.id]
            val nowNext = programmes?.let { EpgGuide.nowNext(it, now) }
            ChannelRow(
                number = index + 1,
                channel = channel,
                favourite = channel.id in favourites,
                lastWatched = index == lastIndex,
                current = nowNext?.now,
                next = nowNext?.next,
                now = now,
                logoPx = logoPx,
                onClick = { onPlay(index) },
                onToggleFavourite = { onToggleFavourite(channel) },
                modifier = if (index == focusIndex) Modifier.focusRequester(requester) else Modifier,
            )
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: IptvChannel,
    favourite: Boolean,
    lastWatched: Boolean,
    current: EpgProgramme?,
    next: EpgProgramme?,
    now: Long,
    logoPx: Int,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    FocusCard(onClick = onClick, onLongClick = onToggleFavourite, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                number.toString(),
                color = if (lastWatched) colors.primary else colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(44.dp),
            )
            ChannelLogo(
                url = channel.logo,
                sizePx = logoPx,
                modifier = Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Icon(Icons.Default.Tv, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (lastWatched) {
                        Spacer(Modifier.width(8.dp))
                        Text(tr("iptv.last_watched"), color = colors.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (current != null) {
                    Text(
                        "${formatClock(current.startMs)}  ${current.title}",
                        color = colors.onSurface.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { current.progress(now) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        drawStopIndicator = {},
                    )
                }
                if (next != null) {
                    Text(
                        strings.format("iptv.next", formatClock(next.startMs), next.title),
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            IconCard(
                if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
                tr(if (favourite) "iptv.favourite_remove" else "iptv.favourite_add"),
                tint = if (favourite) colors.primary else colors.onSurfaceVariant,
                onClick = onToggleFavourite,
            )
        }
    }
}

@Composable
private fun GroupChip(text: String, selected: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, background = if (selected) colors.primaryContainer else colors.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let {
                Icon(it, null, tint = if (selected) colors.onPrimaryContainer else colors.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text, color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

// endregion

// region Общие элементы

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
    }
}

@Composable
private fun IconCard(icon: ImageVector, description: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.size(44.dp), background = Color.Transparent) {
        Icon(icon, description, tint = tint, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun IptvRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = subtitleColor, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
    }
}

// endregion

private fun describeError(error: String, strings: Strings): String = when (error) {
    IptvStore.ERROR_EMPTY -> strings["iptv.error_empty"]
    IptvStore.ERROR_HLS -> strings["iptv.error_hls"]
    else -> strings.format("iptv.error", error)
}

private fun formatClock(ms: Long): String = SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(Date(ms))

private fun formatDate(ms: Long, strings: Strings): String = SimpleDateFormat("d MMM HH:mm", strings.locale).format(Date(ms))

private fun displayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
