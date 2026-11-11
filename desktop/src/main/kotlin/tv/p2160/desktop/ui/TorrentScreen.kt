package tv.p2160.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tv.p2160.torrent.TorrentFile
import tv.p2160.torrent.TorrentStats

data class TorrentRow(
    val id: String,
    val title: String,
    val stats: TorrentStats?,
    val files: List<TorrentFile>,
)

@Composable
fun TorrentScreen(
    rows: List<TorrentRow>,
    error: String?,
    onBack: () -> Unit,
    onMagnet: (String) -> Unit,
    onPickFile: () -> Unit,
    onPlay: (String, TorrentFile) -> Unit,
    onRemove: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    var magnet by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("Торренты", onBack)
        Message("Файл начинает играть, когда скачано начало. Перемотка заказывает нужный кусок. Раздача идёт, пока приложение открыто.")
        Field("Magnet или ссылка на .torrent", magnet, { magnet = it }, onTyping)
        AccentButton("Добавить", magnet.isNotBlank()) { onMagnet(magnet.trim()); magnet = "" }
        AccentButton("Файл .torrent", onPickFile)
        error?.let { ErrorText(it) }
        rows.forEach { row ->
            val stats = row.stats
            val detail = when {
                stats == null -> ""
                !stats.hasMetadata -> "метаданные, пиры ${stats.peers}"
                else -> "${(stats.primaryProgress * 100).toInt()}%, ${stats.downloadRate / 1024} КБ/с, пиры ${stats.peers}"
            }
            ClickRow(row.title, detail) {}
            row.files.filter { it.isPlayable }.forEach { file ->
                ClickRow("  ${file.name}", formatSize(file.size)) { onPlay(row.id, file) }
            }
            AccentButton("Удалить", { onRemove(row.id) })
        }
    }
}
