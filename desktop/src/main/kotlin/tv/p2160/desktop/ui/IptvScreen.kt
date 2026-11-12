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
import tv.p2160.core.iptv.IptvChannel

@Composable
fun IptvScreen(
    channels: List<IptvChannel>,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onChannel: (IptvChannel) -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = channels.filter {
        filter.isBlank() || it.name.contains(filter, true) || it.group.orEmpty().contains(filter, true)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("IPTV", onBack)
        Message("Плейлист M3U. Если это HLS-манифест, он откроется как один поток.")
        AccentButton("Файл плейлиста", onOpenFile)
        Field("Ссылка на плейлист", url, { url = it }, onTyping)
        AccentButton(if (busy) "Загрузка" else "Открыть ссылку", url.isNotBlank() && !busy) { onOpenUrl(url.trim()) }
        error?.let { ErrorText(it) }
        if (channels.isNotEmpty()) {
            Field("Фильтр", filter, { filter = it }, onTyping)
            Message("Каналов: ${shown.size}")
            shown.take(500).forEach { channel ->
                ClickRow(channel.name, channel.group.orEmpty()) { onChannel(channel) }
            }
            if (shown.size > 500) Message("Показаны первые 500. Сузьте фильтр.")
        }
    }
}
