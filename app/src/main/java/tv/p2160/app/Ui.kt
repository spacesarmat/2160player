package tv.p2160.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import tv.p2160.core.i18n.tr
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Потолок колонки на обычном экране, dp. На широком кадре (телефон лёжа, ТВ) он ещё
 * ограничивается долей ширины: 720 dp на плотности 600 — это почти весь экран.
 */
const val ReadableContentMaxDp = 720f

/**
 * Ширина колонки настроек и таких же экранов, dp.
 * Широкий кадр (ширина ≥ 1,5 высоты): не шире 60 % экрана и не шире 720 dp.
 * Иначе — до 720 dp, но не шире самого экрана. Портрет телефона остаётся на всю ширину.
 */
fun readableContentMaxDp(widthDp: Float, heightDp: Float): Float {
    if (widthDp <= 0f) return 0f
    val wide = heightDp > 0f && widthDp >= heightDp * 1.5f
    val cap = if (wide) min(ReadableContentMaxDp, widthDp * 0.6f) else ReadableContentMaxDp
    return cap.coerceIn(min(320f, widthDp), widthDp)
}

@Composable
fun Modifier.readableColumn(): Modifier {
    val config = LocalConfiguration.current
    val max = readableContentMaxDp(config.screenWidthDp.toFloat(), config.screenHeightDp.toFloat())
    return this.widthIn(max = max.dp).fillMaxWidth()
}

/** Карточка с заметным фокусом для пульта и обычным нажатием для пальца. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    background: Color = MaterialTheme.colorScheme.surface,
    /** Переключатель: TalkBack объявляет роль и «включено/выключено». */
    checked: Boolean? = null,
    /** Один из взаимоисключающих пунктов (чип, радио). */
    selected: Boolean? = null,
    contentDescription: String? = null,
    /** Склеить текст карточки в одно объявление (плитки, «Продолжить», история). */
    mergeDescendants: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "cardScale")
    val shape = RoundedCornerShape(16.dp)
    val stateText = when {
        checked == true -> tr("a11y.on")
        checked == false -> tr("a11y.off")
        selected == true -> tr("a11y.selected")
        selected == false -> tr("a11y.unselected")
        else -> null
    }
    val activate = when {
        checked != null -> Modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = null,
            role = Role.Switch,
            onValueChange = { onClick() },
        )
        selected != null -> Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = null,
            role = Role.RadioButton,
            onClick = onClick,
        )
        else -> Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
    val merge = mergeDescendants || checked != null || selected != null || contentDescription != null
    Box(
        modifier
            .heightIn(min = 48.dp)
            .scale(scale)
            .clip(shape)
            .background(background)
            .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .then(activate)
            .semantics(mergeDescendants = merge) {
                if (contentDescription != null) this.contentDescription = contentDescription
                if (stateText != null) stateDescription = stateText
            },
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
