package tv.p2160.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbServer
import tv.p2160.core.source.smb.SmbServers

/** Найденный в сети по mDNS сервер (`_smb._tcp`): Synology, QNAP, TrueNAS, macOS, Samba с avahi. */
data class DiscoveredHost(val name: String, val host: String)

@Composable
fun NetworkScreen(
    servers: SmbServers,
    onBack: () -> Unit,
    onOpen: (SmbServer) -> Unit,
) {
    val list by servers.servers.collectAsStateWithLifecycle()
    val discovered = rememberDiscoveredHosts()
    var editing by remember { mutableStateOf<SmbServer?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }   // предзаполненный адрес
    val colors = MaterialTheme.colorScheme
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.width(12.dp))
            Text(tr("net.title"), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.id }) { server ->
                ServerRow(
                    icon = Icons.Default.Dns,
                    title = server.name.ifBlank { server.host },
                    subtitle = "\\\\${server.host}\\${server.share}" + if (server.path.isNotEmpty()) "\\" + server.path.replace('/', '\\') else "",
                    onClick = { onOpen(server) },
                    onLongClick = { editing = server },
                    modifier = if (server == list.first()) Modifier.focusRequester(firstFocus) else Modifier,
                    trailing = {
                        FocusCard(onClick = { editing = server }, modifier = Modifier.size(40.dp), background = Color.Transparent) {
                            Icon(Icons.Default.Edit, tr("net.edit"), tint = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
                        }
                    },
                )
            }
            item(key = "add") {
                ServerRow(
                    icon = Icons.Default.Add,
                    title = tr("net.add"),
                    subtitle = tr("net.add_hint"),
                    onClick = { adding = "" },
                    modifier = if (list.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
            item(key = "disc-title") { SectionTitle(tr("net.discovered")) }
            if (discovered.isEmpty()) {
                item(key = "searching") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(tr("net.searching"), color = colors.onSurfaceVariant)
                    }
                }
            }
            items(discovered, key = { "d" + it.host + it.name }) { host ->
                ServerRow(
                    icon = Icons.Default.Wifi,
                    title = host.name,
                    subtitle = host.host,
                    onClick = { adding = "\\\\${host.host}\\" },
                )
            }
        }
    }

    adding?.let { address ->
        ServerDialog(initial = null, initialAddress = address, servers = servers, onDismiss = { adding = null })
    }
    editing?.let { server ->
        ServerDialog(initial = server, initialAddress = null, servers = servers, onDismiss = { editing = null })
    }
}

@Composable
private fun ServerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun ServerDialog(initial: SmbServer?, initialAddress: String?, servers: SmbServers, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var address by remember {
        mutableStateOf(
            initial?.let { "\\\\${it.host}\\${it.share}" + if (it.path.isNotEmpty()) "\\" + it.path.replace('/', '\\') else "" }
                ?: initialAddress.orEmpty()
        )
    }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var user by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var domain by remember { mutableStateOf(initial?.domain.orEmpty()) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val parsed = SmbServer.parseAddress(address) ?: run { error = strings["net.bad_address"]; return }
        val server = SmbServer(
            id = initial?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            host = parsed.host, share = parsed.share, path = parsed.path,
            username = user.trim(), password = password, domain = domain.trim(),
        )
        checking = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SmbConnections.test(context, server) } }
            checking = false
            result.onSuccess { servers.save(server); onDismiss() }
                .onFailure { e ->
                    val msg = e.message.orEmpty()
                    error = when {
                        "STATUS_LOGON_FAILURE" in msg || "STATUS_ACCESS_DENIED" in msg -> strings["net.err_login"]
                        "STATUS_BAD_NETWORK_NAME" in msg -> strings["net.err_share"]
                        "STATUS_OBJECT_NAME_NOT_FOUND" in msg || "STATUS_OBJECT_PATH_NOT_FOUND" in msg -> strings["net.err_path"]
                        e is java.net.UnknownHostException || e is java.net.ConnectException || e is java.net.SocketTimeoutException -> strings["net.err_host"]
                        else -> strings.format("net.error", msg.ifBlank { e.javaClass.simpleName })
                    }
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (initial == null) "net.add" else "net.edit")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(address, { address = it }, label = { Text(tr("net.address")) }, placeholder = { Text("\\\\192.168.1.10\\Video") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(name, { name = it }, label = { Text(tr("net.name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(user, { user = it }, label = { Text(tr("net.user")) }, supportingText = { Text(tr("net.guest")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(
                        password, { password = it }, label = { Text(tr("net.password")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { OutlinedTextField(domain, { domain = it }, label = { Text(tr("net.domain")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                if (checking) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("net.checking"))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !checking) { Text(tr("net.save")) } },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { servers.delete(initial.id); onDismiss() }) { Text(tr("net.delete"), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(tr("app.cancel")) }
            }
        },
    )
}

/** Поиск SMB-серверов в локальной сети через mDNS/DNS-SD. */
@Composable
private fun rememberDiscoveredHosts(): List<DiscoveredHost> {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<DiscoveredHost>() }
    DisposableEffect(Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses.firstOrNull()?.hostAddress
                            else resolved.host?.hostAddress
                            host ?: return
                            main.post {
                                val item = DiscoveredHost(resolved.serviceName, host)
                                if (found.none { it.host == host }) found += item
                            }
                        }
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        runCatching { nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
        onDispose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }
    return found
}
