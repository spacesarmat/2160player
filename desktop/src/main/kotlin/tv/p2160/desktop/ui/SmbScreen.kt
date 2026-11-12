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
import tv.p2160.desktop.media.SmbEntry
import tv.p2160.desktop.media.SmbSaved

@Composable
fun SmbScreen(
    saved: List<SmbSaved>,
    entries: List<SmbEntry>,
    shares: List<String>,
    pathLabel: String,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onConnect: (SmbSaved) -> Unit,
    onShare: (String) -> Unit,
    onEntry: (SmbEntry) -> Unit,
    onUp: () -> Unit,
    onTyping: (Boolean) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(saved.firstOrNull()?.host.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(saved.firstOrNull()?.user.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved.firstOrNull()?.password.orEmpty()) }
    var domain by rememberSaveable { mutableStateOf(saved.firstOrNull()?.domain.orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenHeader("SMB", onBack)
        Message("SMB 2/3. Гостевой вход, если пользователь пустой. Пароль хранится в локальном файле библиотеки.")
        Field("Сервер", host, { host = it }, onTyping)
        Field("Пользователь", user, { user = it }, onTyping)
        Field("Пароль", password, { password = it }, onTyping, password = true)
        Field("Домен", domain, { domain = it }, onTyping)
        AccentButton(if (busy) "Подключение" else "Подключиться", !busy && host.isNotBlank()) {
            onConnect(SmbSaved(host.trim(), user, password, domain))
        }
        if (saved.size > 1) {
            Message("Сохранённые серверы")
            saved.drop(1).forEach { server ->
                ClickRow(server.host, server.user) {
                    host = server.host
                    user = server.user
                    password = server.password
                    domain = server.domain
                }
            }
        }
        error?.let { ErrorText(it) }
        if (pathLabel.isNotEmpty()) {
            TextPath(pathLabel)
            AccentButton("Вверх", onUp)
        }
        shares.forEach { share -> ClickRow(share, "папка") { onShare(share) } }
        entries.forEach { entry ->
            val detail = if (entry.directory) "папка" else formatSize(entry.size)
            ClickRow(entry.name, detail) { onEntry(entry) }
        }
    }
}

@Composable
private fun TextPath(text: String) {
    androidx.compose.material3.Text(text, color = P2160Colors.muted)
}

internal fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val units = listOf("КБ", "МБ", "ГБ", "ТБ")
    var value = bytes.toDouble()
    var unit = "Б"
    for (name in units) {
        value /= 1024.0
        unit = name
        if (value < 1024) break
    }
    return "%.1f %s".format(value, unit)
}
