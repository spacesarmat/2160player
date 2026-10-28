package tv.p2160.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.p2160.core.api.Chapter
import tv.p2160.core.api.SegmentType
import tv.p2160.core.engine.TimeInput
import tv.p2160.core.i18n.tr

/** «+1:30» / «−0:20» для накопленной перемотки. */
fun formatDelta(ms: Long): String = (if (ms >= 0) "+" else "−") + formatTime(kotlin.math.abs(ms))

/** Превью при перетаскивании: кадр (если удалось получить), целевое время и смещение. */
@Composable
fun ScrubPreview(targetMs: Long, deltaMs: Long, frame: Bitmap?, chapter: Chapter?, theme: PlayerTheme, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(theme.scrim).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (frame != null) {
            Image(
                frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.width(240.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(formatTime(targetMs), color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(formatDelta(deltaMs), color = theme.accent, style = MaterialTheme.typography.titleSmall)
        chapter?.title?.let {
            Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        }
    }
}

@Composable
fun SpeedBoostBadge(theme: PlayerTheme, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(theme.scrim).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("2×", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.FastForward, null, tint = theme.accent, modifier = Modifier.size(20.dp))
    }
}

/** Ввод цифрами с пульта: одна цифра - проценты (5 → 50 %), несколько - время (1230 → 12:30). */
@Composable
fun DigitEntryOverlay(buffer: String, theme: PlayerTheme, modifier: Modifier = Modifier) {
    val text = if (buffer.length == 1) tr("player.percent", buffer.toInt() * 10)
    else TimeInput.parse(buffer)?.let(::formatTime) ?: buffer
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(theme.scrim).padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tr("player.goto_title"), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        Text("→ $text", color = Color.White, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Кнопка «Пропустить вступление» и т.п. Сама берёт фокус, чтобы на пульте хватало OK. */
@Composable
fun SkipButton(type: SegmentType, onClick: () -> Unit, theme: PlayerTheme, modifier: Modifier = Modifier, takeFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(type) { if (takeFocus) runCatching { focus.requestFocus() } }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier
            .focusRequester(focus)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) theme.accent else Color.White.copy(alpha = 0.92f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr(
                when (type) {
                    SegmentType.INTRO -> "player.skip_intro"
                    SegmentType.RECAP -> "player.skip_recap"
                    SegmentType.CREDITS -> "player.skip_credits"
                    SegmentType.PREVIEW -> "player.skip_preview"
                }
            ),
            color = if (focused) theme.onAccent else Color.Black,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.SkipNext, null, tint = if (focused) theme.onAccent else Color.Black, modifier = Modifier.size(20.dp))
    }
}

/**
 * Карточка «Дальше» в углу. Фокус не забирает, пока [takeFocus] ложь:
 * пульт попадает на кнопки, только если пользователь сам навёлся.
 */
@Composable
fun NextEpisodeCard(
    title: String?,
    coverPath: String?,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    theme: PlayerTheme,
    modifier: Modifier = Modifier,
    takeFocus: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(takeFocus) { if (takeFocus) runCatching { focus.requestFocus() } }
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, coverPath) {
        value = coverPath?.let { path ->
            withContext(Dispatchers.IO) { runCatching { decodePreview(context, path) }.getOrNull() }
        }
    }
    Column(
        modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(theme.surface.copy(alpha = 0.96f))
            .border(1.dp, theme.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Text(tr("player.up_next"), color = theme.muted, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(theme.scrim),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    title ?: tr("player.up_next"),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        if (preview != null && !title.isNullOrBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(title, color = theme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onPlayNow,
                modifier = Modifier.focusRequester(focus).focusProperties { canFocus = takeFocus },
            ) { Text(tr("player.play_now"), color = theme.accent) }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.focusProperties { canFocus = takeFocus },
            ) { Text(tr("player.cancel"), color = theme.muted) }
        }
    }
}

private fun decodePreview(context: android.content.Context, source: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openPreview(context, source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = openPreview(context, source)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
    return bitmap.asImageBitmap()
}

private fun openPreview(context: android.content.Context, source: String): java.io.InputStream? =
    if ("://" in source) {
        val uri = android.net.Uri.parse(source)
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: runCatching { java.net.URL(source).openStream() }.getOrNull()
    } else {
        java.io.File(source).takeIf { it.isFile }?.inputStream()
    }

@Composable
fun GoToTimeDialog(durationMs: Long, onGo: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = TimeInput.parse(text)?.takeIf { durationMs <= 0 || it <= durationMs }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("player.goto_title")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() || it == ':' }.take(8) },
                singleLine = true,
                placeholder = { Text(tr("player.goto_hint")) },
                supportingText = { Text(parsed?.let(::formatTime) ?: tr("player.goto_hint")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { parsed?.let(onGo) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onGo) }, enabled = parsed != null) { Text(tr("player.goto")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("player.cancel")) } },
    )
}
