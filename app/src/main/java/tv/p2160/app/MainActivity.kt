package tv.p2160.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.p2160.core.api.MediaEntry
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.Player2160
import tv.p2160.core.i18n.I18n
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.resume.ResumeEntry
import tv.p2160.core.source.smb.SmbEntry
import tv.p2160.core.source.smb.SmbServers
import androidx.compose.runtime.remember
import tv.p2160.core.ui.P2160Theme
import tv.p2160.core.ui.PlayerThemes

class MainActivity : ComponentActivity() {

    private val settings by lazy { Player2160.settings(this) }
    private val i18n by lazy { I18n.get(this) }

    private val openMedia = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        // Сохраняем доступ, чтобы файл открывался из истории и после перезапуска.
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        play(uri, null)
    }

    private val importTranslation = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val t = i18n.current
        runCatching { i18n.import(uri) }
            .onSuccess { pack ->
                settings.update { it.copy(language = pack.code) }
                toast(i18n.current.format("settings.language_imported", pack.name))
            }
            .onFailure { toast(t.format("settings.language_import_error", it.message)) }
    }

    private val exportTemplate = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(i18n.exportTemplate().toByteArray()) }
        }.onSuccess { toast(i18n.current["settings.language_exported"]) }
            .onFailure { toast(it.message ?: "error") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Player2160.resumeStore(this)

        setContent {
            val s by settings.state.collectAsStateWithLifecycle()
            val strings by i18n.strings.collectAsStateWithLifecycle()
            var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
            var serverId by rememberSaveable { mutableStateOf<String?>(null) }
            val servers = remember { SmbServers.get(this) }

            BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.NETWORK) { screen = Screen.HOME }

            CompositionLocalProvider(LocalStrings provides strings) {
                P2160Theme(PlayerThemes.byId(s.themeId)) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            store = store,
                            onOpenFile = ::pickMedia,
                            onOpenUrl = { url -> play(Uri.parse(url.trim()), null) },
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenNetwork = { screen = Screen.NETWORK },
                            onPlayEntry = ::playEntry,
                        )
                        Screen.NETWORK -> NetworkScreen(
                            servers = servers,
                            onBack = { screen = Screen.HOME },
                            onOpen = { serverId = it.id; screen = Screen.BROWSE },
                        )
                        Screen.BROWSE -> {
                            val server = servers.servers.collectAsStateWithLifecycle().value.firstOrNull { it.id == serverId }
                            if (server == null) {
                                screen = Screen.NETWORK
                            } else {
                                BrowserScreen(
                                    server = server,
                                    store = store,
                                    onBack = { screen = Screen.NETWORK },
                                    onPlay = { files, index -> playSmb(files, index) },
                                    onPlayDisc = { path -> play(path.toUri(), path.name) },
                                )
                            }
                        }
                        Screen.SETTINGS -> SettingsScreen(
                            settingsStore = settings,
                            i18n = i18n,
                            onBack = { screen = Screen.HOME },
                            onImportTranslation = { importTranslation.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            onExportTemplate = { exportTemplate.launch("2160player-translation.json") },
                            onClearHistory = {
                                store.clear()
                                toast(strings["app.history_cleared"])
                            },
                        )
                    }
                }
            }
        }
    }

    private fun pickMedia() {
        try {
            openMedia.launch(arrayOf("video/*", "audio/*", "application/x-mpegURL", "application/octet-stream"))
        } catch (_: ActivityNotFoundException) {
            // На части Android TV-приставок нет системного выбора файлов.
            toast(i18n.current["app.no_file_picker"])
        }
    }

    private fun playEntry(entry: ResumeEntry) = play(Uri.parse(entry.uri), entry.title)

    private fun play(uri: Uri, title: String?) {
        if (uri.scheme.isNullOrBlank()) return
        Player2160.play(this, PlaybackRequest(listOf(MediaEntry(uri, title))))
    }

    private fun playSmb(files: List<SmbEntry>, index: Int) {
        if (index !in files.indices) return
        val items = files.map { MediaEntry(it.path.toUri(), it.name.substringBeforeLast('.')) }
        Player2160.play(this, PlaybackRequest(items, startIndex = index))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    enum class Screen { HOME, SETTINGS, NETWORK, BROWSE }
}
