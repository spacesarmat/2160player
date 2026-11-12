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
import tv.p2160.core.source.dlna.DlnaContainer
import tv.p2160.core.source.dlna.DlnaItem
import tv.p2160.core.source.dlna.DlnaObject
import tv.p2160.core.source.dlna.DlnaServer

@Composable
fun DlnaScreen(
    servers: List<DlnaServer>,
    crumbs: List<String>,
    objects: List<DlnaObject>,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onAddress: (String) -> Unit,
    onServer: (DlnaServer) -> Unit,
    onObject: (DlnaObject) -> Unit,
    onUp: () -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("DLNA", onBack)
        Message("Поиск по SSDP или адрес описания, например 127.0.0.1:8200.")
        AccentButton(if (busy) "Поиск" else "Искать серверы", !busy, onSearch)
        Field("Адрес вручную", address, { address = it }, onTyping)
        AccentButton("Открыть адрес", address.isNotBlank() && !busy) { onAddress(address.trim()) }
        error?.let { ErrorText(it) }
        if (crumbs.isNotEmpty()) {
            Message(crumbs.joinToString(" / "))
            AccentButton("Вверх", onUp)
        }
        servers.forEach { server ->
            ClickRow(server.friendlyName, server.host) { onServer(server) }
        }
        objects.forEach { obj ->
            val detail = when (obj) {
                is DlnaContainer -> "папка"
                is DlnaItem -> obj.kind.name.lowercase()
            }
            ClickRow(obj.title, detail) { onObject(obj) }
        }
    }
}
