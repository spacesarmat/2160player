package tv.p2160.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Usb
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
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.iptv.M3uParser
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.subtitle.SubtitleSupport
import java.io.File
import java.util.Locale

/** Накопитель в корне обзора: внутренняя память, флешка, карта памяти. */
data class LocalRoot(
    val dir: File,
    /** null — внутренняя память (название берётся из перевода). */
    val name: String?,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

enum class LocalKind { FOLDER, VIDEO, AUDIO, DISC, SUBTITLE, PLAYLIST, OTHER }

val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")

/** Тип элемента по имени. Каталог BDMV — диск Blu-ray, образ .iso — тоже. */
fun localKind(file: File, isDirectory: Boolean = file.isDirectory): LocalKind {
    if (isDirectory) return if (file.name.equals("BDMV", true)) LocalKind.DISC else LocalKind.FOLDER
    return when (file.extension.lowercase()) {
        in VIDEO_EXTENSIONS -> LocalKind.VIDEO
        in AUDIO_EXTENSIONS -> LocalKind.AUDIO
        ISO_EXTENSION -> LocalKind.DISC
        in SubtitleSupport.EXTENSIONS -> if (file.extension.equals("xml", true)) LocalKind.OTHER else LocalKind.SUBTITLE
        in PLAYLIST_EXTENSIONS -> LocalKind.PLAYLIST
        else -> LocalKind.OTHER
    }
}

/** «Серия 2» раньше «Серии 10»: числа внутри имени сравниваются как числа. */
val NaturalNameOrder: Comparator<String> = Comparator { a, b ->
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return@Comparator na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return@Comparator c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return@Comparator c
            i++
            j++
        }
    }
    (a.length - i) - (b.length - j)
}

/**
 * Содержимое папки для обзора: сначала папки, потом медиафайлы, по имени; скрытые (с точкой) не показываются.
 * null — папку прочитать нельзя (нет доступа).
 */
fun listLocalMedia(dir: File): List<File>? {
    val files = dir.listFiles() ?: return null
    return files.asSequence()
        .filter { !it.name.startsWith(".") }
        .map { it to it.isDirectory }
        .filter { (f, isDir) -> localKind(f, isDir) != LocalKind.OTHER }
        .sortedWith(compareBy<Pair<File, Boolean>> { !it.second }.thenBy(NaturalNameOrder) { it.first.name })
        .map { it.first }
        .toList()
}

/** Накопители устройства. Блокирующий вызов (statfs) — с фонового потока. */
fun localRoots(context: Context): List<LocalRoot> {
    val roots = LinkedHashMap<String, LocalRoot>()
    fun key(dir: File) = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
    fun add(dir: File, name: String?, removable: Boolean) {
        if (!dir.isDirectory) return
        val k = key(dir)
        if (k in roots) return
        roots[k] = LocalRoot(dir, name, removable, runCatching { dir.freeSpace }.getOrDefault(0), runCatching { dir.totalSpace }.getOrDefault(0))
    }

    @Suppress("DEPRECATION")
    add(Environment.getExternalStorageDirectory(), null, false)

    val manager = context.getSystemService(StorageManager::class.java)
    val volumes = runCatching { manager?.storageVolumes.orEmpty() }.getOrDefault(emptyList())
    for (volume in volumes) {
        if (volume.isPrimary) continue
        val state = volume.state
        if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
        val dir = if (Build.VERSION.SDK_INT >= 30) volume.directory else volume.uuid?.let { File("/storage", it) }
        dir ?: continue
        add(dir, volume.getDescription(context), volume.isRemovable)
    }

    // Запасной путь (Android 7–10 и нестандартные приставки): всё смонтированное в /storage.
    runCatching { File("/storage").listFiles() }.getOrNull().orEmpty()
        .filter { it.isDirectory && it.name != "self" && it.name != "emulated" && !it.name.startsWith(".") }
        .sortedBy { it.name }
        .forEach { add(it, it.name, true) }

    return roots.values.toList()
}

/** Какие разрешения нужны для чтения файлов на этой версии Android. */
fun storagePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/** На Android 13+ достаточно хотя бы одного из «Видео» / «Аудио». */
fun hasStoragePermission(context: Context): Boolean =
    storagePermissions().any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Запрос на воспроизведение локального плейлиста .m3u/.m3u8 (HLS-манифест играется как поток). */
private fun playlistRequest(file: File): PlaybackRequest? {
    if (file.length() > 8L shl 20) return null
    val parsed = M3uParser.parse(file.readText())
    if (parsed.looksLikeHls) {
        return PlaybackRequest(listOf(MediaEntry(Uri.fromFile(file), file.nameWithoutExtension, mimeType = "application/x-mpegURL")))
    }
    val parent = file.parentFile
    val items = parsed.channels.mapNotNull { ch ->
        val url = ch.url.trim()
        val uri = when {
            url.isEmpty() -> return@mapNotNull null
            "://" in url || url.startsWith("content:", true) -> url.toUri()
            else -> {
                val path = url.replace('\\', '/')
                Uri.fromFile(if (path.startsWith("/")) File(path) else File(parent, path))
            }
        }
        MediaEntry(uri, ch.name.ifBlank { null })
    }
    return if (items.isEmpty()) null else PlaybackRequest(items)
}

/**
 * Встроенный обзор файлов на устройстве: внутренняя память, флешки, карты памяти.
 * Нужен на Android TV-приставках, где нет системного выбора файлов.
 * [onPlay] получает все видео/аудио папки и индекс выбранного — как в обзоре SMB.
 */
@Composable
fun LocalBrowserScreen(
    store: ResumeStore,
    onBack: () -> Unit,
    onPlay: (files: List<File>, index: Int) -> Unit,
    onPlayDisc: (disc: File, title: String) -> Unit,
    onPlayRequest: (PlaybackRequest) -> Unit,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val changes by store.changes.collectAsState()

    var granted by remember { mutableStateOf(hasStoragePermission(context)) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it } || hasStoragePermission(context)
        askedOnce = true
        val activity = context as? Activity
        permanentlyDenied = !granted && activity != null &&
            storagePermissions().none { activity.shouldShowRequestPermissionRationale(it) }
    }

    // Корень, в который вошли, и текущая папка; null — список накопителей.
    var rootPath by rememberSaveable { mutableStateOf<String?>(null) }
    var dirPath by rememberSaveable { mutableStateOf<String?>(null) }
    var roots by remember { mutableStateOf<List<LocalRoot>?>(null) }
    var entries by remember { mutableStateOf<List<File>?>(null) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val lastFocus = remember { mutableStateMapOf<String, String>() }

    // Вернулись из настроек или вставили флешку — перечитываем.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val now = hasStoragePermission(context)
        if (now != granted) granted = now
        if (now) permanentlyDenied = false
        reload++
    }

    LaunchedEffect(granted, dirPath, reload) {
        if (!granted) return@LaunchedEffect
        val path = dirPath
        error = false
        if (path == null) {
            roots = withContext(Dispatchers.IO) { runCatching { localRoots(context) }.getOrDefault(emptyList()) }
        } else {
            val list = withContext(Dispatchers.IO) { runCatching { listLocalMedia(File(path)) }.getOrNull() }
            if (list == null) error = true
            entries = list
        }
    }

    fun goUp() {
        val path = dirPath
        val root = rootPath
        when {
            path == null -> onBack()
            root == null || path == root -> { dirPath = null; entries = null }
            else -> {
                val parent = File(path).parentFile?.path
                dirPath = if (parent != null && (parent == root || parent.startsWith("$root/"))) parent else null
                entries = null
            }
        }
    }
    BackHandler(onBack = ::goUp)

    fun openDir(path: String) {
        entries = null
        dirPath = path
    }

    val currentRoot = roots?.firstOrNull { it.dir.path == rootPath }
    val rootLabel = currentRoot?.name ?: tr("local.internal")

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = ::goUp, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                val path = dirPath
                Text(
                    when {
                        path == null -> tr("local.title")
                        path == rootPath -> rootLabel
                        else -> File(path).name
                    },
                    style = MaterialTheme.typography.titleLarge, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    path ?: tr("local.subtitle"),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            !granted -> PermissionState(
                permanentlyDenied = permanentlyDenied && askedOnce,
                onGrant = { permissionLauncher.launch(storagePermissions()) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.onFailure { permissionLauncher.launch(storagePermissions()) }
                },
            )

            dirPath == null -> {
                val list = roots
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(tr("local.no_storages"), color = colors.onSurfaceVariant, modifier = Modifier.padding(32.dp))
                    else -> {
                        val requester = remember(list) { FocusRequester() }
                        LaunchedEffect(list) { runCatching { requester.requestFocus() } }
                        val focusPath = lastFocus[""]
                        val focusTarget = list.firstOrNull { it.dir.path == focusPath } ?: list.first()
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item(key = "title") { SectionTitle(tr("local.storages")) }
                            items(list, key = { it.dir.path }) { root ->
                                val label = root.name ?: strings["local.internal"]
                                LocalRow(
                                    icon = when {
                                        root.name == null -> Icons.Default.Smartphone
                                        label.contains("usb", ignoreCase = true) -> Icons.Default.Usb
                                        else -> Icons.Default.SdStorage
                                    },
                                    title = label,
                                    subtitle = if (root.totalBytes > 0) {
                                        strings.format("local.space", formatLocalSize(root.freeBytes), formatLocalSize(root.totalBytes))
                                    } else if (root.removable) strings["local.removable"] else null,
                                    modifier = if (root == focusTarget) Modifier.focusRequester(requester) else Modifier,
                                    onClick = {
                                        lastFocus[""] = root.dir.path
                                        rootPath = root.dir.path
                                        openDir(root.dir.path)
                                    },
                                )
                            }
                            if (Build.VERSION.SDK_INT >= 30) {
                                item(key = "hint") {
                                    Text(
                                        tr("local.scoped_hint"),
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 16.dp).widthIn(max = 640.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                val dir = File(dirPath!!)
                val list = entries
                when {
                    error -> Column(Modifier.fillMaxWidth().padding(32.dp)) {
                        Text(tr("local.error"), color = colors.error)
                        TextButton(onClick = { reload++ }) { Text(tr("player.retry")) }
                    }
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(tr("local.empty"), color = colors.onSurfaceVariant, modifier = Modifier.padding(32.dp))
                    else -> {
                        val playable = list.filter { val k = localKind(it); k == LocalKind.VIDEO || k == LocalKind.AUDIO }
                        val bdmv = list.firstOrNull { it.isDirectory && it.name.equals("BDMV", true) }
                        val focusName = lastFocus[dir.path]
                        val focusTarget = list.firstOrNull { it.name == focusName } ?: list.first()
                        val requester = remember(dir, list) { FocusRequester() }
                        LaunchedEffect(dir, list) { runCatching { requester.requestFocus() } }
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = list.indexOf(focusTarget).coerceAtLeast(0))

                        fun playPlaylist(file: File) {
                            scope.launch {
                                val request = withContext(Dispatchers.IO) { runCatching { playlistRequest(file) }.getOrNull() }
                                if (request != null) onPlayRequest(request)
                                else Toast.makeText(context, strings["local.playlist_error"], Toast.LENGTH_SHORT).show()
                            }
                        }

                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (bdmv != null) {
                                item(key = "disc") {
                                    LocalRow(
                                        icon = Icons.Default.Album, title = tr("browse.bluray_play"), subtitle = tr("browse.bluray"),
                                        onClick = { onPlayDisc(bdmv, dir.name) },
                                    )
                                }
                            }
                            items(list, key = { it.path }) { file ->
                                val kind = localKind(file)
                                val resume = remember(file, changes) {
                                    if (kind == LocalKind.VIDEO || kind == LocalKind.AUDIO) store.get(ResumeStore.keyFor(Uri.fromFile(file))) else null
                                }
                                LocalRow(
                                    icon = when (kind) {
                                        LocalKind.FOLDER -> Icons.Default.Folder
                                        LocalKind.AUDIO -> Icons.Default.AudioFile
                                        LocalKind.DISC -> Icons.Default.Album
                                        LocalKind.SUBTITLE -> Icons.Default.Subtitles
                                        LocalKind.PLAYLIST -> Icons.AutoMirrored.Filled.PlaylistPlay
                                        else -> if (resume?.finished == true) Icons.Default.CheckCircle else Icons.Default.Movie
                                    },
                                    title = when (kind) {
                                        LocalKind.FOLDER, LocalKind.SUBTITLE, LocalKind.PLAYLIST -> file.name
                                        LocalKind.DISC -> if (file.isDirectory) file.name else file.nameWithoutExtension
                                        else -> file.nameWithoutExtension
                                    },
                                    subtitle = when (kind) {
                                        LocalKind.FOLDER -> null
                                        LocalKind.DISC -> if (file.isDirectory) tr("browse.bluray") else "${tr("browse.bluray")} · ${formatLocalSize(file.length())}"
                                        LocalKind.SUBTITLE -> tr("local.subtitle_file")
                                        LocalKind.PLAYLIST -> tr("local.playlist")
                                        else -> formatLocalSize(file.length())
                                    },
                                    progress = resume?.takeIf { !it.finished && it.positionMs > 0 }?.progress,
                                    modifier = if (file == focusTarget) Modifier.focusRequester(requester) else Modifier,
                                    onClick = {
                                        lastFocus[dir.path] = file.name
                                        when (kind) {
                                            LocalKind.FOLDER -> openDir(file.path)
                                            LocalKind.DISC -> if (file.isDirectory) onPlayDisc(file, dir.name) else onPlayDisc(file, file.nameWithoutExtension)
                                            LocalKind.PLAYLIST -> playPlaylist(file)
                                            // Субтитры подхватываются плеером сами — открываем видео, к которому они относятся.
                                            LocalKind.SUBTITLE -> {
                                                val video = playable.firstOrNull { localKind(it) == LocalKind.VIDEO && file.name.startsWith(it.nameWithoutExtension) }
                                                if (video != null) onPlay(playable, playable.indexOf(video))
                                                else Toast.makeText(context, strings["local.subtitle_no_video"], Toast.LENGTH_SHORT).show()
                                            }
                                            else -> onPlay(playable, playable.indexOf(file))
                                        }
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

@Composable
private fun PermissionState(permanentlyDenied: Boolean, onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    LaunchedEffect(permanentlyDenied) { runCatching { requester.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(32.dp).widthIn(max = 640.dp)) {
        Icon(Icons.Default.FolderOpen, null, tint = colors.primary, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(tr("local.permission_title"), style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            tr(if (permanentlyDenied) "local.permission_denied" else "local.permission_text"),
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        FocusCard(
            onClick = if (permanentlyDenied) onOpenSettings else onGrant,
            modifier = Modifier.focusRequester(requester),
            background = colors.primary,
        ) {
            Text(
                tr(if (permanentlyDenied) "local.permission_settings" else "local.permission_grant"),
                color = colors.onPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun LocalRow(
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

private fun formatLocalSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}
