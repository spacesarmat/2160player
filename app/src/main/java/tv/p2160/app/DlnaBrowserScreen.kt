package tv.p2160.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.p2160.core.api.Player2160
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.source.dlna.ContentDirectory
import tv.p2160.core.source.dlna.DlnaContainer
import tv.p2160.core.source.dlna.DlnaItem
import tv.p2160.core.source.dlna.DlnaMediaKind
import tv.p2160.core.source.dlna.DlnaObject
import tv.p2160.core.source.dlna.DlnaPlayback
import tv.p2160.core.source.dlna.DlnaServer
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Запуск воспроизведения элемента DLNA с остальными элементами папки как плейлистом. */
fun playDlna(context: Context, items: List<DlnaItem>, index: Int) {
    val request = DlnaPlayback.request(items, index) ?: return
    Player2160.play(context, request)
}

/**
 * Обзор папок медиасервера DLNA. «Назад» — на уровень выше, из корня — [onBack].
 * [onPlay] получает видео (или аудио) текущей папки и индекс выбранного — см. [playDlna].
 */
@Composable
fun DlnaBrowserScreen(
    server: DlnaServer,
    store: ResumeStore,
    onBack: () -> Unit,
    onPlay: (items: List<DlnaItem>, index: Int) -> Unit,
) {
    // Путь: пары id/название, корень — "0". Переживает поворот экрана.
    var path by rememberSaveable(server.udn) { mutableStateOf(listOf(ContentDirectory.ROOT_ID, server.friendlyName)) }
    val currentId = path[path.size - 2]
    val titles = path.filterIndexed { i, _ -> i % 2 == 1 }
    val cache = remember(server.udn) { HashMap<String, List<DlnaObject>>() }
    var objects by remember { mutableStateOf<List<DlnaObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val lastFocus = remember { mutableStateMapOf<String, String>() }
    val changes by store.changes.collectAsState()
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current

    LaunchedEffect(currentId, reload) {
        error = null
        cache[currentId]?.let { objects = it; return@LaunchedEffect }
        objects = null
        withContext(Dispatchers.IO) {
            runCatching { ContentDirectory(server).browseAll(currentId, limit = 5000) }
        }.onSuccess { list ->
            // Показываем папки и проигрываемые видео/аудио; картинки и прочее плеер не открывает.
            val visible = list.filter { o ->
                o is DlnaContainer || o is DlnaItem && (o.kind == DlnaMediaKind.VIDEO || o.kind == DlnaMediaKind.AUDIO) && o.bestResource != null
            }
            cache[currentId] = visible
            objects = visible
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    fun goUp() {
        if (path.size <= 2) onBack() else path = path.dropLast(2)
    }
    BackHandler(onBack = ::goUp)

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = ::goUp, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(titles.last(), style = MaterialTheme.typography.titleLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    titles.joinToString(" › "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val list = objects
        when {
            error != null -> Column(Modifier.fillMaxWidth().padding(32.dp)) {
                Text(tr("dlna.error", error.orEmpty()), color = colors.error)
                val retry = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
                TextButton(onClick = { reload++ }, modifier = Modifier.focusRequester(retry)) { Text(tr("player.retry")) }
            }
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(tr("dlna.empty"), color = colors.onSurfaceVariant, modifier = Modifier.padding(32.dp))
            else -> {
                val focusId = lastFocus[currentId]
                val focusTarget = list.firstOrNull { it.id == focusId } ?: list.first()
                val requester = remember(currentId, list) { FocusRequester() }
                LaunchedEffect(currentId, list) { runCatching { requester.requestFocus() } }
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = list.indexOf(focusTarget).coerceAtLeast(0))

                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list, key = { it.id }) { obj ->
                        val modifier = if (obj == focusTarget) Modifier.focusRequester(requester) else Modifier
                        when (obj) {
                            is DlnaContainer -> DlnaRow(
                                icon = Icons.Default.Folder,
                                imageUrl = obj.albumArtUrl,
                                title = obj.title,
                                subtitle = obj.childCount?.let { strings.format("dlna.items", it) },
                                modifier = modifier,
                                onClick = {
                                    lastFocus[currentId] = obj.id
                                    path = path + listOf(obj.id, obj.title)
                                },
                            )
                            is DlnaItem -> {
                                val url = obj.bestResource?.url
                                val resume = remember(url, changes) { url?.let { store.get(ResumeStore.keyFor(it.toUri())) } }
                                DlnaRow(
                                    icon = when {
                                        obj.kind == DlnaMediaKind.AUDIO -> Icons.Default.AudioFile
                                        resume?.finished == true -> Icons.Default.CheckCircle
                                        else -> Icons.Default.Movie
                                    },
                                    imageUrl = obj.albumArtUrl,
                                    title = obj.title,
                                    subtitle = describeItem(obj, strings["dlna.transcoded"], strings["dlna.subtitles"]),
                                    progress = resume?.takeIf { !it.finished && it.positionMs > 0 }?.progress,
                                    modifier = modifier,
                                    onClick = {
                                        lastFocus[currentId] = obj.id
                                        // Плейлист — элементы того же типа из этой папки.
                                        val same = list.filterIsInstance<DlnaItem>().filter { it.kind == obj.kind }
                                        onPlay(same, same.indexOf(obj))
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

/** «1:43:38 · 3840×1600 · 15.2 GB · субтитры». */
private fun describeItem(item: DlnaItem, transcoded: String, subtitles: String): String? {
    val res = item.bestResource
    val parts = listOfNotNull(
        item.durationMs?.let(::formatDuration),
        if (res?.width != null && res.height != null) "${res.width}×${res.height}" else null,
        res?.size?.let(::formatBytes),
        if (res?.isConverted == true) transcoded else null,
        if (item.subtitles.isNotEmpty()) subtitles else null,
        item.artist?.takeIf { item.kind == DlnaMediaKind.AUDIO },
    )
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

@Composable
internal fun DlnaRow(
    icon: ImageVector,
    imageUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NetImage(imageUrl, icon, 36.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                trailing?.invoke()
            }
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
            }
        }
    }
}

/** Картинка по http (иконка сервера, обложка) с запасной векторной иконкой. */
@Composable
internal fun NetImage(url: String?, fallback: ImageVector, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState(url?.let { NetImages.cached(it) }, url) {
        if (url != null && value == null) value = withContext(Dispatchers.IO) { NetImages.load(url, px) }
    }
    val image = bitmap
    if (image != null) {
        Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)))
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Icon(fallback, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        }
    }
}

/** Небольшой кэш картинок без сторонних библиотек: уменьшаем при декодировании до нужного размера. */
private object NetImages {
    private val cache = LruCache<String, ImageBitmap>(200)
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun cached(url: String): ImageBitmap? = cache.get(url)

    fun load(url: String, targetPx: Int): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        val bitmap = runCatching {
            val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 3000
                readTimeout = 5000
                try { inputStream.use { it.readBytes() } } finally { disconnect() }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
        if (bitmap != null) cache.put(url, bitmap) else failed += url
        return bitmap
    }
}
