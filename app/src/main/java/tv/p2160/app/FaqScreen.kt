package tv.p2160.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tv.p2160.core.i18n.tr

/**
 * «Справка и FAQ»: вопросы по разделам, ответ раскрывается по нажатию (палец и пульт).
 * Тексты — в языковом пакете `assets/i18n/faq/<код>.json` (ключи `faq.<id>.q` / `faq.<id>.a`).
 */
internal object Faq {
    const val ISSUES_URL = "https://github.com/spacesarmat/2160player/issues"

    /** Разделы: ключ заголовка → вопросы (id). */
    val sections: List<Pair<String, List<String>>> = listOf(
        "faq.section.install" to listOf("which_apk", "update", "update_failed", "tv_install", "share"),
        "faq.section.picture" to listOf("scale", "dv", "hdr_dark", "no_video"),
        "faq.section.sound" to listOf("no_sound", "night", "audio_delay", "background"),
        "faq.section.subtitles" to listOf("external_subs", "subs_delay", "dual_subs", "smart_tracks"),
        "faq.section.sources" to listOf("open_file_tv", "smb", "dlna", "iptv", "covers"),
        "faq.section.torrents" to listOf("torrent_peers", "torrent_stop", "torrent_space"),
        "faq.section.devices" to listOf("handoff", "handoff_code", "handoff_mobile"),
        "faq.section.other" to listOf("sleep", "skip_intro", "languages", "embed"),
    )
}

@Composable
fun FaqScreen(onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val firstFocus = remember { FocusRequester() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.size(12.dp))
            Text(tr("faq.title"), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground)
        }
        LazyColumn(
            Modifier.fillMaxWidth().widthIn(max = 900.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Faq.sections.forEachIndexed { sectionIndex, (title, ids) ->
                item(key = title) { SectionTitle(tr(title)) }
                ids.forEachIndexed { i, id ->
                    item(key = id) {
                        val expanded = open == id
                        FocusCard(
                            onClick = { open = if (expanded) null else id },
                            modifier = Modifier.fillMaxWidth().then(if (sectionIndex == 0 && i == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        ) {
                            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr("faq.$id.q"), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
                                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = colors.onSurfaceVariant)
                                }
                                AnimatedVisibility(expanded) {
                                    Text(
                                        tr("faq.$id.a"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item(key = "report") {
                SectionTitle(tr("faq.section.help"))
                FocusCard(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Faq.ISSUES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, null, tint = colors.primary)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(tr("faq.report"), style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                            Text(Faq.ISSUES_URL, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
