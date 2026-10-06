package tv.p2160.core.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.p2160.core.i18n.I18n

enum class DecoderPreference {
    /** Аппаратные декодеры, FFmpeg — если устройство формат не умеет. */
    AUTO,
    /** Только аппаратные (MediaCodec). */
    HARDWARE,
    /** Всегда FFmpeg (программно). Помогает при «зелёном экране» и рассинхроне на дешёвых боксах. */
    FFMPEG,
}

enum class ResizeMode { FIT, FILL, ZOOM }

/** Что делать с вступлениями и титрами. */
enum class SkipMode { OFF, BUTTON, AUTO }

enum class SubtitleEdge { NONE, OUTLINE, SHADOW }

data class SubtitleStyle(
    val sizeScale: Float = 1f,
    val textColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000,
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val bottomPadding: Float = 0.08f,
    /** Игнорировать цвета/шрифты, заданные внутри ASS/SSA/TTML. */
    val overrideEmbeddedStyles: Boolean = false,
)

data class Settings(
    /** Код языкового пакета или [I18n.SYSTEM]. */
    val language: String = I18n.SYSTEM,
    val themeId: String = "cinema",
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val defaultSpeed: Float = 1f,
    val decoder: DecoderPreference = DecoderPreference.AUTO,
    val preferredAudioLanguages: List<String> = listOf("ru"),
    val preferredSubtitleLanguages: List<String> = listOf("ru"),
    val autoResume: Boolean = true,
    val seekStepSeconds: Int = 10,
    val resizeMode: ResizeMode = ResizeMode.FIT,
    val autoPlayNext: Boolean = true,
    val skipMode: SkipMode = SkipMode.BUTTON,
    /** «Ночной звук»: сжатие динамики и выделение диалогов. */
    val nightMode: Boolean = false,
    /** Включать ночной звук автоматически в интервале [nightStartMinute, nightEndMinute). */
    val nightAuto: Boolean = false,
    /** Минуты от полуночи: 23:00 = 1380. */
    val nightStartMinute: Int = 23 * 60,
    val nightEndMinute: Int = 10 * 60,
) {
    /** Нужен ли ночной звук сейчас: включён вручную или попадаем в расписание. */
    fun nightModeAt(minuteOfDay: Int): Boolean =
        nightMode || (nightAuto && NightSchedule.contains(nightStartMinute, nightEndMinute, minuteOfDay))
}

/** Интервал времени суток, в т.ч. через полночь (23:00–10:00). */
object NightSchedule {
    fun contains(start: Int, end: Int, minute: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    fun nowMinute(): Int = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

    fun format(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
}


/** Настройки плеера на SharedPreferences; наблюдаемы через [state]. */
class PlayerSettings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("p2160_settings", Context.MODE_PRIVATE)

    private val i18n = I18n.get(context)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    init {
        i18n.apply(_state.value.language)
    }

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        write(next)
        if (next.language != _state.value.language) i18n.apply(next.language)
        _state.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            language = prefs.getString("language", d.language)!!,
            themeId = prefs.getString("theme", d.themeId)!!,
            subtitleStyle = SubtitleStyle(
                sizeScale = prefs.getFloat("sub_size", d.subtitleStyle.sizeScale),
                textColor = prefs.getLong("sub_color", d.subtitleStyle.textColor),
                backgroundColor = prefs.getLong("sub_bg", d.subtitleStyle.backgroundColor),
                edge = enumOr(prefs.getString("sub_edge", null), d.subtitleStyle.edge),
                bottomPadding = prefs.getFloat("sub_bottom", d.subtitleStyle.bottomPadding),
                overrideEmbeddedStyles = prefs.getBoolean("sub_override", d.subtitleStyle.overrideEmbeddedStyles),
            ),
            defaultSpeed = prefs.getFloat("speed", d.defaultSpeed),
            decoder = enumOr(prefs.getString("decoder", null), d.decoder),
            preferredAudioLanguages = prefs.getString("audio_langs", null)?.splitLangs() ?: d.preferredAudioLanguages,
            preferredSubtitleLanguages = prefs.getString("sub_langs", null)?.splitLangs() ?: d.preferredSubtitleLanguages,
            autoResume = prefs.getBoolean("auto_resume", d.autoResume),
            seekStepSeconds = prefs.getInt("seek_step", d.seekStepSeconds),
            resizeMode = enumOr(prefs.getString("resize", null), d.resizeMode),
            autoPlayNext = prefs.getBoolean("auto_next", d.autoPlayNext),
            skipMode = enumOr(prefs.getString("skip_mode", null), d.skipMode),
            nightMode = prefs.getBoolean("night_mode", d.nightMode),
            nightAuto = prefs.getBoolean("night_auto", d.nightAuto),
            nightStartMinute = prefs.getInt("night_start", d.nightStartMinute),
            nightEndMinute = prefs.getInt("night_end", d.nightEndMinute),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putString("language", s.language)
            .putString("theme", s.themeId)
            .putFloat("sub_size", s.subtitleStyle.sizeScale)
            .putLong("sub_color", s.subtitleStyle.textColor)
            .putLong("sub_bg", s.subtitleStyle.backgroundColor)
            .putString("sub_edge", s.subtitleStyle.edge.name)
            .putFloat("sub_bottom", s.subtitleStyle.bottomPadding)
            .putBoolean("sub_override", s.subtitleStyle.overrideEmbeddedStyles)
            .putFloat("speed", s.defaultSpeed)
            .putString("decoder", s.decoder.name)
            .putString("audio_langs", s.preferredAudioLanguages.joinToString(","))
            .putString("sub_langs", s.preferredSubtitleLanguages.joinToString(","))
            .putBoolean("auto_resume", s.autoResume)
            .putInt("seek_step", s.seekStepSeconds)
            .putString("resize", s.resizeMode.name)
            .putBoolean("auto_next", s.autoPlayNext)
            .putString("skip_mode", s.skipMode.name)
            .putBoolean("night_mode", s.nightMode)
            .putBoolean("night_auto", s.nightAuto)
            .putInt("night_start", s.nightStartMinute)
            .putInt("night_end", s.nightEndMinute)
            .apply()
    }

    private fun String.splitLangs() = split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    companion object {
        @Volatile private var instance: PlayerSettings? = null

        fun get(context: Context): PlayerSettings =
            instance ?: synchronized(this) { instance ?: PlayerSettings(context).also { instance = it } }
    }
}
