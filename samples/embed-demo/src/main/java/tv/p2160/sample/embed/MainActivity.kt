package tv.p2160.sample.embed

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.p2160.core.api.IntentApi
import tv.p2160.core.api.NowPlaying
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.PlaybackResult
import tv.p2160.core.api.Player2160

class MainActivity : ComponentActivity() {

    /** Последний результат воспроизведения (из PlayContract или из отдельного приложения). */
    private var lastResult by mutableStateOf<String?>(null)

    // (c) Запуск плеера библиотеки с возвратом позиции.
    private val playForResult = registerForActivityResult(Player2160.PlayContract()) { result ->
        lastResult = "PlayContract: " + describe(result)
    }

    // (a) Отдельное приложение 2160 Player через Intent API; результат — в формате MX Player.
    private val externalPlayer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        lastResult = "Intent API: " + describe(IntentApi.parseResult(r.resultCode, r.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    DemoScreen()
                }
            }
        }
    }

    @Composable
    private fun DemoScreen() {
        var url by rememberSaveable { mutableStateOf(SAMPLE_URL) }
        val nowPlaying by Player2160.nowPlaying.collectAsStateWithLifecycle()

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("2160 Player — пример встраивания", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL или smb://, file://, content://") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(onClick = { Player2160.play(this@MainActivity, request(url)) }, Modifier.fillMaxWidth()) {
                    Text("Player2160.play()")
                }
                Button(onClick = { playForResult.launch(request(url)) }, Modifier.fillMaxWidth()) {
                    Text("PlayContract — вернуть позицию")
                }
                Button(
                    onClick = { startActivity(EmbeddedPlayerActivity.intent(this@MainActivity, Uri.parse(url.trim()), "Встроенный PlayerScreen")) },
                    Modifier.fillMaxWidth(),
                ) {
                    Text("PlayerScreen в своей Activity")
                }
                OutlinedButton(onClick = { openInStandaloneApp(url) }, Modifier.fillMaxWidth()) {
                    Text("Открыть в приложении 2160 Player (Intent API)")
                }

                lastResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "В плеере справа сверху есть кнопка «Поделиться ссылкой» — её добавляет SampleApp " +
                        "через Player2160.registerAction().",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            nowPlaying?.let { NowPlayingBar(it) }
        }
    }

    /** (e) Мини-плеер «Сейчас играет» на основе Player2160.nowPlaying. */
    @Composable
    private fun NowPlayingBar(np: NowPlaying) {
        Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (np.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${formatTime(np.positionMs)} / ${formatTime(np.durationMs)}")
                }
                if (np.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }

    private fun request(url: String): PlaybackRequest =
        PlaybackRequest.single(Uri.parse(url.trim()), title = "Пример из embed-demo")

    /**
     * Intent API без библиотеки: только строковые ключи, поэтому этот код работает
     * в любом приложении. Константы IntentApi здесь — лишь для удобства.
     */
    private fun openInStandaloneApp(url: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(url.trim()), "video/*")
            .putExtra(IntentApi.EXTRA_TITLE, "Из embed-demo")      // "title"
            .putExtra(IntentApi.EXTRA_RETURN_RESULT, true)        // "return_result"
            .putExtra(IntentApi.EXTRA_HEADERS, arrayOf("User-Agent", "EmbedDemo/1.0"))
        val pkg = STANDALONE_PACKAGES.firstOrNull(::isInstalled)
        if (pkg != null) {
            // Явный компонент: работает и для схем, которых нет в intent-filter (например, smb://).
            intent.component = ComponentName(pkg, PLAYER_ACTIVITY)
            externalPlayer.launch(intent)
        } else {
            // Приложение не установлено — предлагаем любой видеоплеер (без возврата результата).
            try {
                startActivity(Intent.createChooser(intent, "Открыть в…"))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "Нет подходящего плеера", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isInstalled(pkg: String): Boolean =
        try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun describe(result: PlaybackResult?): String =
        if (result == null) "результата нет (плеер закрыт без RESULT_OK)"
        else "остановлено на ${formatTime(result.positionMs)} из ${formatTime(result.durationMs)}" +
            if (result.completed) ", досмотрено до конца" else ""

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    companion object {
        private const val SAMPLE_URL =
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"

        /** Релизная и отладочная сборки отдельного приложения. */
        private val STANDALONE_PACKAGES = listOf("tv.p2160.player", "tv.p2160.player.debug")
        private const val PLAYER_ACTIVITY = "tv.p2160.core.Player2160Activity"
    }
}
