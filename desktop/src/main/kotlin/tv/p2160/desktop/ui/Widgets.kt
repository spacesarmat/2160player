package tv.p2160.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccentButton("Назад", onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = P2160Colors.onSurface)
    }
}

@Composable
fun AccentButton(text: String, onClick: () -> Unit) = AccentButton(text, true, onClick)

@Composable
fun AccentButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = P2160Colors.accent,
            contentColor = P2160Colors.onAccent,
        ),
    ) { Text(text) }
}

@Composable
fun ClickRow(title: String, detail: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = P2160Colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail.isNotEmpty()) {
            Text(detail, color = P2160Colors.muted, maxLines = 1)
        }
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().onFocusChanged { onTyping(it.isFocused) },
    )
}

@Composable
fun Message(text: String) {
    Text(text, color = P2160Colors.muted, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun ErrorText(text: String) {
    Text(text, color = ColorDanger, modifier = Modifier.padding(vertical = 8.dp))
}

private val ColorDanger = androidx.compose.ui.graphics.Color(0xFFFF8A80)

@Composable
fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(P2160Colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}
