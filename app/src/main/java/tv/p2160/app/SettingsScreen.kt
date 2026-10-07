package tv.p2160.app

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import tv.p2160.app.handoff.Handoff
import tv.p2160.app.handoff.HandoffAuth
import tv.p2160.app.update.UpdateState
import tv.p2160.app.update.Updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.p2160.core.i18n.I18n
import tv.p2160.core.i18n.LocalStrings
import tv.p2160.core.i18n.tr
import tv.p2160.core.settings.DecoderPreference
import tv.p2160.core.settings.PlayerSettings
import tv.p2160.core.settings.SkipMode
import tv.p2160.core.settings.NightSchedule
import tv.p2160.core.settings.SubtitleEdge
import tv.p2160.core.ui.PlayerThemes
import tv.p2160.core.ui.SPEED_PRESETS
import tv.p2160.core.ui.formatSpeed

private data class Choice<T>(val label: String, val value: T, val hint: String? = null, val swatch: Color? = null)

private class ChoiceRequest(val title: String, val options: List<Choice<*>>, val selected: Any?, val onSelect: (Any?) -> Unit)

private class TextRequest(val title: String, val hint: String, val initial: String, val onSubmit: (String) -> Unit)

@Composable
fun SettingsScreen(
    settingsStore: PlayerSettings,
    i18n: I18n,
    onBack: () -> Unit,
    onOpenFaq: () -> Unit,
    onImportTranslation: () -> Unit,
    onExportTemplate: () -> Unit,
    onClearHistory: () -> Unit,
) {
    val s by settingsStore.state.collectAsStateWithLifecycle()
    val strings = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    var choice by remember { mutableStateOf<ChoiceRequest?>(null) }
    var textInput by remember { mutableStateOf<TextRequest?>(null) }
    val packs = remember(strings) { i18n.available() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    fun <T> ask(title: String, options: List<Choice<T>>, selected: T, onSelect: (T) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        choice = ChoiceRequest(title, options, selected) { onSelect(it as T) }
    }

    fun update(transform: (tv.p2160.core.settings.Settings) -> tv.p2160.core.settings.Settings) = settingsStore.update(transform)

    val languageOptions = listOf(Choice(strings["settings.language_system"], I18n.SYSTEM)) +
        packs.map { Choice(it.name, it.code, hint = if (it.builtIn) null else strings["settings.language_user"]) }
    val currentPack = packs.firstOrNull { it.code == s.language }

    val textColors = listOf(
        Choice(strings["settings.color_white"], 0xFFFFFFFFL, swatch = Color.White),
        Choice(strings["settings.color_yellow"], 0xFFFFE14DL, swatch = Color(0xFFFFE14D)),
        Choice(strings["settings.color_cyan"], 0xFF5CE1FFL, swatch = Color(0xFF5CE1FF)),
        Choice(strings["settings.color_green"], 0xFF7CFF8AL, swatch = Color(0xFF7CFF8A)),
    )
    val backgrounds = listOf(
        Choice(strings["settings.bg_none"], 0x00000000L),
        Choice(strings["settings.bg_semi"], 0x99000000L),
        Choice(strings["settings.bg_black"], 0xFF000000L),
    )
    val edges = listOf(
        Choice(strings["settings.edge_none"], SubtitleEdge.NONE),
        Choice(strings["settings.edge_outline"], SubtitleEdge.OUTLINE),
        Choice(strings["settings.edge_shadow"], SubtitleEdge.SHADOW),
    )
    val decoders = listOf(
        Choice(strings["settings.decoder_auto"], DecoderPreference.AUTO, strings["settings.decoder_auto_hint"]),
        Choice(strings["settings.decoder_hw"], DecoderPreference.HARDWARE, strings["settings.decoder_hw_hint"]),
        Choice(strings["settings.decoder_ffmpeg"], DecoderPreference.FFMPEG, strings["settings.decoder_ffmpeg_hint"]),
    )
    val sizes = listOf(0.6f, 0.75f, 0.9f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f).map { Choice("${(it * 100).toInt()}%", it) }
    val steps = listOf(5, 10, 15, 20, 30, 60).map { Choice(strings.format("settings.seconds", it), it) }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.size(12.dp))
            Text(tr("settings.title"), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
        }

        LazyColumn(
            Modifier.fillMaxWidth().widthIn(max = 900.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionTitle(tr("settings.section_interface")) }
            item {
                SettingRow(
                    title = tr("settings.language"),
                    value = if (s.language == I18n.SYSTEM) strings["settings.language_system"] else currentPack?.name ?: s.language,
                    modifier = Modifier.focusRequester(firstFocus),
                ) { ask(strings["settings.language"], languageOptions, s.language) { v -> update { it.copy(language = v) } } }
            }
            item { SettingRow(tr("settings.language_import"), hint = tr("settings.language_import_hint"), onClick = onImportTranslation) }
            item { SettingRow(tr("settings.language_export"), hint = tr("settings.language_export_hint"), onClick = onExportTemplate) }
            if (currentPack != null && !currentPack.builtIn) {
                item {
                    SettingRow(tr("settings.language_remove"), value = currentPack.name) {
                        i18n.removeUserPack(currentPack.code)
                        update { it.copy(language = I18n.SYSTEM) }
                    }
                }
            }
            item {
                SettingRow(tr("settings.theme"), value = tr(PlayerThemes.byId(s.themeId).nameKey)) {
                    ask(strings["settings.theme"], PlayerThemes.all.map { Choice(strings[it.nameKey], it.id, swatch = it.accent) }, s.themeId) { v ->
                        update { it.copy(themeId = v) }
                    }
                }
            }

            item { SectionTitle(tr("settings.section_subtitles")) }
            item {
                SettingRow(tr("settings.subtitle_size"), value = "${(s.subtitleStyle.sizeScale * 100).toInt()}%") {
                    ask(strings["settings.subtitle_size"], sizes, s.subtitleStyle.sizeScale) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(sizeScale = v)) } }
                }
            }
            item {
                SettingRow(tr("settings.subtitle_color"), value = textColors.firstOrNull { it.value == s.subtitleStyle.textColor }?.label) {
                    ask(strings["settings.subtitle_color"], textColors, s.subtitleStyle.textColor) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(textColor = v)) } }
                }
            }
            item {
                SettingRow(tr("settings.subtitle_background"), value = backgrounds.firstOrNull { it.value == s.subtitleStyle.backgroundColor }?.label) {
                    ask(strings["settings.subtitle_background"], backgrounds, s.subtitleStyle.backgroundColor) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(backgroundColor = v)) } }
                }
            }
            item {
                SettingRow(tr("settings.subtitle_edge"), value = edges.first { it.value == s.subtitleStyle.edge }.label) {
                    ask(strings["settings.subtitle_edge"], edges, s.subtitleStyle.edge) { v -> update { it.copy(subtitleStyle = it.subtitleStyle.copy(edge = v)) } }
                }
            }
            item {
                ToggleRow(tr("settings.subtitle_override"), tr("settings.subtitle_override_hint"), s.subtitleStyle.overrideEmbeddedStyles) { v ->
                    update { it.copy(subtitleStyle = it.subtitleStyle.copy(overrideEmbeddedStyles = v)) }
                }
            }
            item {
                SettingRow(tr("settings.sub_lang"), value = s.preferredSubtitleLanguages.joinToString(", ")) {
                    textInput = TextRequest(strings["settings.sub_lang"], strings["settings.langs_hint"], s.preferredSubtitleLanguages.joinToString(", ")) { v ->
                        update { it.copy(preferredSubtitleLanguages = parseLangs(v)) }
                    }
                }
            }

            item { SectionTitle(tr("settings.section_playback")) }
            item { ToggleRow(tr("settings.auto_resume"), null, s.autoResume) { v -> update { it.copy(autoResume = v) } } }
            item { ToggleRow(tr("settings.smart_tracks"), tr("settings.smart_tracks_hint"), s.smartTracks) { v -> update { it.copy(smartTracks = v) } } }
            item {
                val context = LocalContext.current
                SettingRow(tr("settings.smart_reset")) {
                    tv.p2160.core.engine.TrackPreferences.get(context).clear()
                    android.widget.Toast.makeText(context, strings["settings.smart_reset_done"], android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            item { ToggleRow(tr("settings.auto_next"), null, s.autoPlayNext) { v -> update { it.copy(autoPlayNext = v) } } }
            item { ToggleRow(tr("settings.night_mode"), tr("settings.night_mode_hint"), s.nightMode) { v -> update { it.copy(nightMode = v) } } }
            item {
                ToggleRow(
                    tr("settings.night_auto"),
                    tr("settings.night_auto_hint", NightSchedule.format(s.nightStartMinute), NightSchedule.format(s.nightEndMinute)),
                    s.nightAuto,
                ) { v -> update { it.copy(nightAuto = v) } }
            }
            if (s.nightAuto) {
                val times = (0 until 48).map { Choice(NightSchedule.format(it * 30), it * 30) }
                item {
                    SettingRow(tr("settings.night_start"), value = NightSchedule.format(s.nightStartMinute)) {
                        ask(strings["settings.night_start"], times, s.nightStartMinute) { v -> update { it.copy(nightStartMinute = v) } }
                    }
                }
                item {
                    SettingRow(tr("settings.night_end"), value = NightSchedule.format(s.nightEndMinute)) {
                        ask(strings["settings.night_end"], times, s.nightEndMinute) { v -> update { it.copy(nightEndMinute = v) } }
                    }
                }
            }
            item {
                val modes = listOf(
                    Choice(strings["settings.skip_off"], SkipMode.OFF),
                    Choice(strings["settings.skip_button"], SkipMode.BUTTON),
                    Choice(strings["settings.skip_auto"], SkipMode.AUTO),
                )
                SettingRow(tr("settings.skip_mode"), value = modes.first { it.value == s.skipMode }.label, hint = tr("settings.skip_hint")) {
                    ask(strings["settings.skip_mode"], modes, s.skipMode) { v -> update { it.copy(skipMode = v) } }
                }
            }
            item {
                SettingRow(tr("settings.default_speed"), value = "${formatSpeed(s.defaultSpeed)}×") {
                    ask(strings["settings.default_speed"], SPEED_PRESETS.map { Choice("${formatSpeed(it)}×", it) }, s.defaultSpeed) { v -> update { it.copy(defaultSpeed = v) } }
                }
            }
            item {
                SettingRow(tr("settings.seek_step"), value = strings.format("settings.seconds", s.seekStepSeconds)) {
                    ask(strings["settings.seek_step"], steps, s.seekStepSeconds) { v -> update { it.copy(seekStepSeconds = v) } }
                }
            }
            item {
                SettingRow(tr("settings.audio_lang"), value = s.preferredAudioLanguages.joinToString(", ")) {
                    textInput = TextRequest(strings["settings.audio_lang"], strings["settings.langs_hint"], s.preferredAudioLanguages.joinToString(", ")) { v ->
                        update { it.copy(preferredAudioLanguages = parseLangs(v)) }
                    }
                }
            }
            item {
                val current = decoders.first { it.value == s.decoder }
                SettingRow(tr("settings.decoder"), value = current.label, hint = current.hint) {
                    ask(strings["settings.decoder"], decoders, s.decoder) { v -> update { it.copy(decoder = v) } }
                }
            }
            item { SettingRow(tr("app.clear_history"), onClick = onClearHistory) }

            item { SectionTitle(tr("settings.section_minimize")) }
            item { ToggleRow(tr("settings.pip"), tr("settings.pip_hint"), s.pictureInPicture) { v -> update { it.copy(pictureInPicture = v) } } }
            item { ToggleRow(tr("settings.background"), tr("settings.background_hint"), s.backgroundPlayback) { v -> update { it.copy(backgroundPlayback = v) } } }
            item { ToggleRow(tr("settings.background_audio"), tr("settings.background_audio_hint"), s.backgroundAudio) { v -> update { it.copy(backgroundAudio = v) } } }

            item { SectionTitle(tr("settings.section_handoff")) }
            item {
                val context = LocalContext.current
                var mode by remember { mutableStateOf(HandoffAuth.mode) }
                var revision by remember { mutableStateOf(0) }
                val modes = listOf(
                    Choice(strings["settings.handoff_off"], HandoffAuth.Mode.OFF, strings["settings.handoff_off_hint"]),
                    Choice(strings["settings.handoff_daily"], HandoffAuth.Mode.DAILY, strings["settings.handoff_daily_hint"]),
                    Choice(strings["settings.handoff_custom"], HandoffAuth.Mode.CUSTOM, strings["settings.handoff_custom_hint"]),
                )
                fun setMode(m: HandoffAuth.Mode) {
                    HandoffAuth.mode = m
                    mode = m
                    Handoff.restart(context)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingRow(tr("settings.handoff_protection"), value = modes.first { it.value == mode }.label, hint = tr("settings.handoff_protection_hint")) {
                        ask(strings["settings.handoff_protection"], modes, mode) { m ->
                            if (m == HandoffAuth.Mode.CUSTOM && !HandoffAuth.isValidCustomCode(HandoffAuth.customCode)) {
                                textInput = TextRequest(strings["settings.handoff_custom_set"], strings["settings.handoff_custom_rule"], "") { code ->
                                    if (HandoffAuth.isValidCustomCode(code)) { HandoffAuth.customCode = code; setMode(m) }
                                }
                            } else {
                                setMode(m)
                            }
                        }
                    }
                    when (mode) {
                        HandoffAuth.Mode.DAILY -> SettingRow(
                            tr("settings.handoff_code"),
                            value = HandoffAuth.currentCode().chunked(3).joinToString(" "),
                            hint = tr("settings.handoff_code_hint"),
                        ) {}
                        HandoffAuth.Mode.CUSTOM -> SettingRow(tr("settings.handoff_custom_set"), value = "•".repeat(HandoffAuth.customCode.length), hint = tr("settings.handoff_custom_rule")) {
                            textInput = TextRequest(strings["settings.handoff_custom_set"], strings["settings.handoff_custom_rule"], "") { code ->
                                if (HandoffAuth.isValidCustomCode(code)) { HandoffAuth.customCode = code; revision++ }
                            }
                        }
                        HandoffAuth.Mode.OFF -> Unit
                    }
                    val trusted = remember(revision) { HandoffAuth.trustedDevices() }
                    SettingRow(
                        tr("settings.handoff_forget"),
                        hint = if (trusted.isEmpty()) tr("settings.handoff_forget_none") else strings.format("settings.handoff_forget_hint", trusted.joinToString(", ")),
                    ) {
                        HandoffAuth.forgetAll()
                        revision++
                    }
                }
            }

            item { SectionTitle(tr("settings.section_updates")) }
            item {
                val context = LocalContext.current
                var auto by remember { mutableStateOf(Updater.isAutoCheck(context)) }
                ToggleRow(tr("settings.update_auto"), tr("settings.update_auto_hint"), auto) { v ->
                    auto = v
                    Updater.setAutoCheck(context, v)
                }
            }
            item {
                val scope = rememberCoroutineScope()
                val updateState by Updater.state.collectAsStateWithLifecycle()
                SettingRow(
                    tr("settings.update_check"),
                    value = if (updateState == UpdateState.Checking) tr("settings.update_checking") else Updater.currentVersion,
                ) { scope.launch { Updater.check() } }
            }

            item { SectionTitle(tr("settings.section_about")) }
            item { SettingRow(tr("settings.faq"), hint = tr("settings.faq_hint"), icon = Icons.AutoMirrored.Filled.HelpOutline, onClick = onOpenFaq) }
            item {
                val context = LocalContext.current
                SettingRow(tr("settings.donate"), hint = tr("settings.donate_hint"), icon = Icons.Default.Favorite) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Faq.DONATE_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            item {
                val context = LocalContext.current
                SettingRow(tr("settings.author"), value = Faq.AUTHOR, hint = tr("settings.author_hint"), icon = Icons.AutoMirrored.Filled.Send) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Faq.TELEGRAM_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            item {
                var sharing by remember { mutableStateOf(false) }
                SettingRow(tr("share.title"), hint = tr("share.settings_hint"), icon = Icons.Default.Share) { sharing = true }
                if (sharing) tv.p2160.app.share.ShareAppDialog(onDismiss = { sharing = false })
            }
            item {
                val context = LocalContext.current
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
                Text(
                    "2160 Player · " + tr("settings.version", version),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    choice?.let { req ->
        ChoiceDialog(req, onDismiss = { choice = null })
    }
    textInput?.let { req ->
        TextDialog(req, onDismiss = { textInput = null })
    }
}

private fun parseLangs(raw: String): List<String> =
    raw.split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.length in 2..8 }.distinct()

@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    hint: String? = null,
    modifier: Modifier = Modifier,
    /** Значок слева (фирменным цветом) — для пунктов-ссылок вроде «Справка», «Автор». */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = colors.primary)
                Spacer(Modifier.size(16.dp))
            }
            // Длинное значение справа сжимает заголовок и подсказку — тогда показываем его под заголовком.
            val inline = value != null && value.length <= 14
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                if (value != null && !inline) Text(value, color = colors.primary, style = MaterialTheme.typography.bodyMedium)
                if (hint != null) Text(hint, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (inline) {
                Spacer(Modifier.size(16.dp))
                Text(value!!, color = colors.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusCard(onClick = { onToggle(!checked) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSurface, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) Text(hint, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            // Переключатель только показывает состояние: нажатие обрабатывает вся строка (удобно с пульта).
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun ChoiceDialog(req: ChoiceRequest, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(req.title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(req.options) { option ->
                    val selected = option.value == req.selected
                    FocusCard(
                        onClick = { req.onSelect(option.value); onDismiss() },
                        modifier = Modifier.fillMaxWidth().then(
                            if (selected || req.options.none { it.value == req.selected } && option == req.options.first()) Modifier.focusRequester(focus) else Modifier
                        ),
                        background = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            option.swatch?.let {
                                Box(Modifier.size(18.dp).background(it, CircleShape))
                                Spacer(Modifier.size(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(option.label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                option.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}

@Composable
private fun TextDialog(req: TextRequest, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(req.initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(req.title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, supportingText = { Text(req.hint) })
        },
        confirmButton = { TextButton(onClick = { req.onSubmit(value); onDismiss() }) { Text(tr("settings.ok")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("app.cancel")) } },
    )
}
