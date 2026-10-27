package tv.p2160.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tv.p2160.core.i18n.tr

/**
 * Порядок нажатий - порядок предпочтения. Языка нет в списке - его код можно дописать.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LanguagePickerDialog(
    title: String,
    selected: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var order by remember(selected) { mutableStateOf(selected) }
    var custom by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tr("settings.langs_pick"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (order.isNotEmpty()) {
                    Text(
                        order.mapIndexed { i, code -> "${i + 1}. ${LanguageCodes.displayName(code)}" }.joinToString("  →  "),
                        color = colors.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LanguageCodes.common.forEach { code ->
                        val on = code in order
                        FocusCard(
                            onClick = { order = if (on) order.filterNot { it == code } else order + code },
                            selected = on,
                            modifier = Modifier.heightIn(min = 48.dp),
                            background = if (on) colors.primaryContainer else colors.surfaceVariant,
                        ) {
                            Text(
                                LanguageCodes.displayName(code),
                                color = if (on) colors.onPrimaryContainer else colors.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    singleLine = true,
                    label = { Text(tr("settings.langs_custom")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        val parsed = LanguageCodes.parse(custom)
                        if (parsed.isNotEmpty()) {
                            order = (order + parsed).distinct()
                            custom = ""
                        }
                    },
                    enabled = LanguageCodes.parse(custom).isNotEmpty(),
                ) { Text(tr("settings.langs_add")) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(order); onDismiss() }) { Text(tr("settings.ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}
