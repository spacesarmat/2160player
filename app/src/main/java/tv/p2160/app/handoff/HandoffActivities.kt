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

/** Выбор устройства, на которое отправить текущий просмотр (кнопка в плеере). */
class HandoffSendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dialogContent {
            val peers by Handoff.peers.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            var sending by remember { mutableStateOf(false) }
            val strings = LocalStrings.current
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
                                onClick = {
                                    val now = Player2160.nowPlaying.value ?: return@FocusCard
                                    sending = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { Handoff.push(this@HandoffSendActivity, peer, now) }
                                        Toast.makeText(
                                            this@HandoffSendActivity,
                                            if (ok) strings.format("handoff.sent", peer.name) else strings["handoff.failed"],
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                        finish()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Devices, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Text(peer.name)
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
