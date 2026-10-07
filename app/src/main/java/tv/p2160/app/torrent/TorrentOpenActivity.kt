package tv.p2160.app.torrent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.p2160.core.api.Player2160
import tv.p2160.core.i18n.I18n
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.ui.P2160Theme
import tv.p2160.core.ui.PlayerThemes
import tv.p2160.torrent.TorrentEngine

/**
 * Открытие magnet-ссылок и .torrent-файлов извне («Открыть с помощью», браузер):
 * добавляет торрент, ждёт метаданные и показывает выбор файла.
 */
class TorrentOpenActivity : ComponentActivity() {

    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TorrentEngine.install(this)
        incoming.value = extractUri(intent)
        val settings = Player2160.settings(this)
        val i18n = I18n.get(this)

        setContent {
            val s by settings.state.collectAsStateWithLifecycle()
            val strings by i18n.strings.collectAsStateWithLifecycle()
            val uri by incoming
            CompositionLocalProvider(LocalStrings provides strings) {
                P2160Theme(PlayerThemes.byId(s.themeId)) {
                    TorrentScreen(
                        onBack = ::finish,
                        onPlay = { request -> Player2160.play(this, request) },
                        initialUri = uri,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractUri(intent)?.let { incoming.value = it }
    }

    /** magnet:, content://, file:// или http(s) .torrent из data. */
    private fun extractUri(intent: Intent?): Uri? = intent?.data
}
