package tv.p2160.core.api

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Снимок текущего воспроизведения — для виджетов, «продолжить на другом устройстве», аналитики. */
data class NowPlaying(
    /** URI, с которым был запущен плеер (для дисков — путь к ISO/папке, а не внутренний клип). */
    val uri: Uri,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    /** HTTP-заголовки запроса (для сетевых источников). */
    val headers: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Дополнительная кнопка в верхней панели плеера. Регистрируется приложением через
 * [Player2160.registerAction] — так встраивающее приложение добавляет свои функции
 * («Отправить на ТВ», «Добавить в избранное»…), не меняя код библиотеки.
 */
class PlayerAction(
    val id: String,
    val icon: ImageVector,
    /** Подпись (contentDescription); ключ локализации или готовая строка. */
    val label: String,
    val onClick: (context: Context, nowPlaying: NowPlaying?) -> Unit,
)

/**
 * Телегид для эфирных запросов ([PlaybackRequest.liveTv]): подпись под названием канала,
 * обычно текущая передача из EPG. Вызывается с главного потока примерно раз в 30 с —
 * должен отвечать быстро (из памяти), null — подписи нет.
 */
fun interface LiveGuide {
    fun describe(entry: MediaEntry, nowMs: Long): String?
}

/** Глобальные точки расширения плеера. */
object PlayerExtensions {
    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _actions = MutableStateFlow<List<PlayerAction>>(emptyList())
    val actions: StateFlow<List<PlayerAction>> = _actions.asStateFlow()

    /** Настройка движка для новых плееров ([Player2160.config]). */
    @Volatile var config: PlayerConfig = PlayerConfig()

    /** Свой телегид; null — встроенный (каналы из IPTV-плейлистов [tv.p2160.core.iptv.IptvStore]). */
    @Volatile var liveGuide: LiveGuide? = null

    internal fun publish(value: NowPlaying?) {
        _nowPlaying.value = value
    }

    fun register(action: PlayerAction) {
        _actions.value = _actions.value.filterNot { it.id == action.id } + action
    }

    fun unregister(id: String) {
        _actions.value = _actions.value.filterNot { it.id == id }
    }
}
