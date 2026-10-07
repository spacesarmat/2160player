package tv.p2160.app.handoff

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.p2160.app.FocusCard
import tv.p2160.core.api.Player2160
import tv.p2160.core.i18n.I18n
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.ui.P2160Theme
import tv.p2160.core.ui.PlayerThemes
import tv.p2160.core.ui.formatTime

/** Общая обёртка диалоговых экранов: тема и строки приложения. */
private fun ComponentActivity.dialogContent(content: @androidx.compose.runtime.Composable () -> Unit) {
    setContent {
        val strings by I18n.get(this).strings.collectAsStateWithLifecycle()
        val settings by Player2160.settings(this).state.collectAsStateWithLifecycle()
        CompositionLocalProvider(LocalStrings provides strings) {
            P2160Theme(PlayerThemes.byId(settings.themeId)) { content() }
        }
    }
}

/** «Galaxy S21 предлагает продолжить здесь: Фильм, 12:44» — подтверждение входящей передачи. */
class HandoffReceiveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = intent.getStringExtra(EXTRA_SESSION)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return finish()
        val session = Handoff.sessionFromJson(null, json)
        val from = intent.getStringExtra(EXTRA_FROM).orEmpty()
        dialogContent {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            AlertDialog(
                onDismissRequest = ::finish,
                icon = { Icon(Icons.Default.Devices, null) },
                title = { Text(tr("handoff.incoming_title", from)) },
                text = { Text("${session.title}\n${formatTime(session.positionMs)}") },
                confirmButton = {
                    TextButton(onClick = { Handoff.play(this, session); finish() }, modifier = Modifier.focusRequester(focus)) {
                        Text(tr("handoff.watch_here"))
                    }
                },
                dismissButton = { TextButton(onClick = ::finish) { Text(tr("app.cancel")) } },
            )
        }
    }

    companion object {
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_FROM = "from"

        fun show(context: Context, sessionJson: String, from: String) {
            context.startActivity(
                Intent(context, HandoffReceiveActivity::class.java)
                    .putExtra(EXTRA_SESSION, sessionJson)
                    .putExtra(EXTRA_FROM, from)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * Ввод кода, который показывает [peer] (тост «… хочет подключиться» или Настройки → Передача),
 * и сопряжение. [onPaired] вызывается после успеха.
 */
@androidx.compose.runtime.Composable
internal fun PairCodeDialog(peer: Peer, onDismiss: () -> Unit, onPaired: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val strings = LocalStrings.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        if (busy || code.length < 4) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { Handoff.pair(context, peer, code) }
            busy = false
            when (result) {
                HandoffAuth.PairResult.OK -> onPaired()
                HandoffAuth.PairResult.WRONG_CODE -> { error = strings["handoff.code_wrong"]; code = "" }
                HandoffAuth.PairResult.LOCKED -> error = strings["handoff.code_locked"]
                HandoffAuth.PairResult.FAILED -> error = strings["handoff.failed"]
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text(tr("handoff.code_title", peer.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("handoff.code_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.material3.OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filter(Char::isDigit).take(8); error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(e) } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = code.length >= 4 && !busy) { Text(tr("handoff.code_connect")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}

/** Выбор устройства, на которое отправить текущий просмотр (кнопка в плеере). */
class HandoffSendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialogContent {
            val peers by Handoff.peers.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var sending by remember { mutableStateOf(false) }
            var pairing by remember { mutableStateOf<Peer?>(null) }
            val strings = LocalStrings.current

            fun send(peer: Peer) {
                val now = Player2160.nowPlaying.value ?: return
                sending = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { Handoff.push(this@HandoffSendActivity, peer, now) }
                    sending = false
                    if (result == PushResult.NEED_CODE) {
                        pairing = peer
                        return@launch
                    }
                    val ok = result == PushResult.OK
                    // Просмотр продолжился на другом устройстве — здесь ставим на паузу.
                    if (ok) Player2160.pause()
                    Toast.makeText(
                        this@HandoffSendActivity,
                        if (ok) strings.format("handoff.sent", peer.name) else strings["handoff.failed"],
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
            }

            pairing?.let { peer ->
                PairCodeDialog(peer, onDismiss = { pairing = null }, onPaired = { pairing = null; send(peer) })
                return@dialogContent
            }
            AlertDialog(
                onDismissRequest = ::finish,
                title = { Text(tr("handoff.send_title")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (peers.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(tr("handoff.searching"))
                            }
                            Text(tr("handoff.hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        peers.forEach { peer ->
                            FocusCard(
                                onClick = { if (Handoff.needsCode(peer)) pairing = peer else send(peer) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(peer.name, modifier = Modifier.weight(1f))
                                    if (Handoff.needsCode(peer)) Icon(Icons.Default.Lock, tr("handoff.code_needed"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                },
                confirmButton = { TextButton(onClick = ::finish) { Text(tr("app.cancel")) } },
            )
        }
    }
}
