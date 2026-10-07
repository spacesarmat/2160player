package tv.p2160.app.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tv.p2160.core.i18n.tr

/** Диалог обновления поверх любого экрана приложения; сам по себе ничего не показывает в Idle/Checking. */
@Composable
fun UpdateDialog() {
    val state by Updater.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    when (val s = state) {
        is UpdateState.Available -> {
            LaunchedEffect(s) { runCatching { focus.requestFocus() } }
            AlertDialog(
                onDismissRequest = Updater::dismiss,
                title = { Text(tr("update.title")) },
                text = {
                    Column {
                        Text(tr("update.version", s.info.version, Updater.currentVersion))
                        if (s.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                s.info.notes,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { scope.launch { Updater.download(context.applicationContext, s.info) } }, modifier = Modifier.focusRequester(focus)) {
                        Text(tr("update.install"))
                    }
                },
                dismissButton = {
                    Column {
                        TextButton(onClick = Updater::dismiss) { Text(tr("update.later")) }
                        TextButton(onClick = { Updater.skip(context, s.info.version) }) { Text(tr("update.skip")) }
                    }
                },
            )
        }
        is UpdateState.Downloading, UpdateState.Installing -> AlertDialog(
            onDismissRequest = {},
            title = { Text(tr("update.title")) },
            text = {
                Column {
                    if (s is UpdateState.Downloading) {
                        Text(tr("update.downloading", "${(s.progress * 100).toInt()}%"))
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(tr("update.installing"))
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {},
        )
        UpdateState.UpToDate, is UpdateState.Failed -> AlertDialog(
            onDismissRequest = Updater::dismiss,
            text = { Text(if (s is UpdateState.Failed) tr("update.failed", s.message) else tr("update.up_to_date", Updater.currentVersion)) },
            confirmButton = { TextButton(onClick = Updater::dismiss) { Text(tr("update.close")) } },
        )
        else -> Unit
    }
}
