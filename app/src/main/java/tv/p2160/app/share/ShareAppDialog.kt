package tv.p2160.app.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tv.p2160.app.FocusCard
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr

/** Диалог «Поделиться приложением»: файлом, по Wi-Fi с QR-кодом или ссылкой. */
@Composable
fun ShareAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val abis = remember { ShareApp.abis(context) }
    var wifi by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(wifi) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Share, null) },
        title = { Text(tr("share.title")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!wifi) {
                    Option(Icons.Default.Share, tr("share.file"), tr("share.file_hint"), Modifier.focusRequester(focus)) {
                        ShareApp.shareFile(context, strings["share.title"])
                    }
                    Option(Icons.Default.Wifi, tr("share.wifi"), tr("share.wifi_hint")) { wifi = true }
                    Option(Icons.Default.Link, tr("share.link"), tr("share.link_hint")) {
                        ShareApp.shareLink(context, strings["share.link_text"], strings["share.title"])
                    }
                    if (abis.isNotEmpty() && !ShareApp.fitsAllArmDevices(abis)) FullPackage(abis)
                } else {
                    val url = remember { ShareApp.wifiUrl() }
                    if (url == null) {
                        Text(tr("share.wifi_none"))
                    } else {
                        val qr = remember(url) { ShareApp.qr(url, 512).asImageBitmap() }
                        Image(
                            qr, contentDescription = url,
                            modifier = Modifier.align(Alignment.CenterHorizontally).size(220.dp)
                                .clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                        )
                        Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(tr("share.wifi_steps"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (wifi) TextButton(onClick = { wifi = false }, modifier = Modifier.focusRequester(focus)) { Text(tr("player.back")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("update.close")) } },
    )
}

/** Установлен APK под одну архитектуру: статус полного пакета или кнопка «скачать». */
@Composable
private fun FullPackage(abis: Set<String>) {
    val context = LocalContext.current
    val small = MaterialTheme.typography.bodySmall
    when (val full = ShareApp.full.collectAsState().value) {
        is ShareApp.FullState.Ready -> Text(tr("share.full_ready"), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is ShareApp.FullState.Downloading -> {
            Text(tr("share.full_downloading", (full.progress * 100).toInt()), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { full.progress }, modifier = Modifier.fillMaxWidth())
        }
        else -> {
            Text(tr("share.abi_warning", abis.sorted().joinToString(", ")), style = small, color = MaterialTheme.colorScheme.error)
            if (full is ShareApp.FullState.Failed) Text(tr("share.full_failed", full.message), style = small, color = MaterialTheme.colorScheme.error)
            Option(Icons.Default.Download, tr("share.full_get"), tr("share.full_get_hint")) { ShareApp.prepareFull(context, auto = false) }
        }
    }
}

@Composable
private fun Option(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth(), background = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
