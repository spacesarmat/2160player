package tv.p2160.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tv.p2160.desktop.APP_VERSION
import tv.p2160.desktop.media.RecentItem

@Composable
fun HomeScreen(
    recent: List<RecentItem>,
    playerError: String?,
    onOpenFile: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onSmb: () -> Unit,
    onDlna: () -> Unit,
    onIptv: () -> Unit,
    onTorrent: () -> Unit,
    onRecent: (RecentItem) -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    var urlOpen by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("2160 Player", style = MaterialTheme.typography.headlineLarge, color = P2160Colors.onSurface)
        Text("Версия $APP_VERSION", color = P2160Colors.muted)
        if (playerError != null) ErrorText(playerError)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("Открыть файл", onOpenFile)
            AccentButton("Открыть ссылку") { urlOpen = true }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton("SMB", onSmb)
            AccentButton("DLNA", onDlna)
            AccentButton("IPTV", onIptv)
            AccentButton("Торренты", onTorrent)
        }
        Text(
            "Пробел: пауза. Влево и вправо: 10 с. Вверх и вниз: громкость. F: полный экран. M: без звука. Esc: назад. O: открыть файл.",
            color = P2160Colors.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Text("Недавние", style = MaterialTheme.typography.titleMedium, color = P2160Colors.onSurface)
        if (recent.isEmpty()) {
            Message("Пока пусто. Откройте файл или перетащите его в окно.")
        } else {
            recent.forEach { item ->
                ClickRow(item.title.ifBlank { item.key }, item.kind) { onRecent(item) }
            }
        }
        if (urlOpen) {
            Panel {
                Text("Ссылка на файл или поток", color = P2160Colors.onSurface)
                Field("http, rtsp, rtmp, srt", url, { url = it }, onTyping)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccentButton("Играть", url.isNotBlank()) { onOpenUrl(url.trim()); urlOpen = false }
                    AccentButton("Отмена") { urlOpen = false }
                }
            }
        }
    }
}
