package tv.p2160.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.source.dlna.DlnaException
import tv.p2160.core.source.dlna.DlnaServer
import tv.p2160.core.source.dlna.DlnaServers

/**
 * Раздел «Медиасерверы (DLNA)»: найденные по SSDP и добавленные вручную серверы,
 * повторный поиск и добавление по адресу. Поиск запускается при первом показе.
 * Предназначен для размещения внутри одного `item {}` списка экрана «Сеть».
 */
@Composable
fun DlnaServersSection(
    onOpen: (DlnaServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val registry = remember { DlnaServers.get(context) }
    val servers by registry.servers.collectAsStateWithLifecycle()
    val searching by registry.searching.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { registry.refresh() }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(tr("dlna.section"))
        servers.forEach { known ->
            val s = known.server
            DlnaRow(
                icon = Icons.Default.VideoLibrary,
                imageUrl = s.bestIcon()?.url,
                title = s.friendlyName,
                subtitle = listOfNotNull(
                    s.modelName?.takeIf { it.isNotBlank() } ?: s.manufacturer,
                    s.host.ifEmpty { null },
                    if (known.manual) tr("dlna.manual") else null,
                ).joinToString(" · "),
                onClick = { onOpen(s) },
                onLongClick = if (known.manual) ({ registry.removeManual(s.udn) }) else null,
                trailing = if (known.manual) {
                    {
                        FocusCard(onClick = { registry.removeManual(s.udn) }, modifier = Modifier.size(40.dp), background = Color.Transparent) {
                            Icon(Icons.Default.Delete, tr("dlna.remove"), tint = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                        }
                    }
                } else null,
            )
        }
        if (searching) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(tr("dlna.searching"), color = colors.onSurfaceVariant)
            }
        } else {
            if (servers.isEmpty()) Text(tr("dlna.none"), color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            DlnaRow(icon = Icons.Default.Refresh, imageUrl = null, title = tr("dlna.refresh"), subtitle = null, onClick = { registry.refresh() })
        }
        DlnaRow(icon = Icons.Default.Add, imageUrl = null, title = tr("dlna.add"), subtitle = tr("dlna.add_hint"), onClick = { adding = true })
    }

    if (adding) {
        AddDlnaDialog(onDismiss = { adding = false }, onAdded = { adding = false; onOpen(it) }, registry = registry)
    }
}

@Composable
private fun AddDlnaDialog(onDismiss: () -> Unit, onAdded: (DlnaServer) -> Unit, registry: DlnaServers) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        busy = true
        error = null
        scope.launch {
            runCatching { registry.addByAddress(address) }
                .onSuccess { onAdded(it) }
                .onFailure { e ->
                    error = if (e is DlnaException) strings["dlna.not_found"] else strings.format("dlna.error", e.message ?: e.javaClass.simpleName)
                }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("dlna.add")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address, { address = it; error = null },
                    label = { Text(tr("dlna.address")) },
                    placeholder = { Text("192.168.1.10:8096") },
                    supportingText = { Text(tr("dlna.address_hint")) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("dlna.checking"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::connect, enabled = !busy && address.isNotBlank()) { Text(tr("dlna.connect")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}
