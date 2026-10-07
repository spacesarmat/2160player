package tv.p2160.core.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.flow.StateFlow
import tv.p2160.core.Player2160Activity
import tv.p2160.core.resume.ResumeStore
import tv.p2160.core.settings.PlayerSettings

/**
 * Публичный фасад библиотеки.
 *
 * ```kotlin
 * // Простой запуск
 * Player2160.play(context, PlaybackRequest.single(uri, "Фильм"))
 *
 * // С возвратом позиции
 * val launcher = registerForActivityResult(Player2160.PlayContract()) { result -> … }
 * launcher.launch(PlaybackRequest.single(uri).copy(returnResult = true))
 * ```
 */
object Player2160 {

    fun intent(context: Context, request: PlaybackRequest): Intent =
        IntentApi.toIntent(request, Intent(context, Player2160Activity::class.java))

    fun play(context: Context, request: PlaybackRequest) {
        val intent = intent(context, request)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** История и позиции остановки. */
    fun resumeStore(context: Context): ResumeStore = ResumeStore.get(context)

    /** Настройки плеера (тема, субтитры, скорость, декодеры). */
    fun settings(context: Context): PlayerSettings = PlayerSettings.get(context)

    /**
     * Что играет сейчас. null — плеер в этом процессе ещё не запускался; после закрытия остаётся
     * последний снимок с isPlaying = false. Обновляется примерно раз в секунду.
     */
    val nowPlaying: StateFlow<NowPlaying?> get() = PlayerExtensions.nowPlaying

    /** Добавить свою кнопку в верхнюю панель плеера. Повторная регистрация с тем же id заменяет кнопку. */
    fun registerAction(action: PlayerAction) = PlayerExtensions.register(action)

    fun unregisterAction(id: String) = PlayerExtensions.unregister(id)

    /**
     * Поставить текущее воспроизведение на паузу (с любого потока). Например, после передачи
     * просмотра на другое устройство. Ничего не делает, если плеер не открыт.
     */
    fun pause() = tv.p2160.core.engine.PlaybackSessions.pause()

    /** Свой телегид для эфирных запросов ([PlaybackRequest.liveTv]); null — встроенный IPTV. */
    fun setLiveGuide(guide: LiveGuide?) {
        PlayerExtensions.liveGuide = guide
    }

    class PlayContract : ActivityResultContract<PlaybackRequest, PlaybackResult?>() {
        override fun createIntent(context: Context, input: PlaybackRequest): Intent =
            intent(context, input.copy(returnResult = true))

        override fun parseResult(resultCode: Int, intent: Intent?): PlaybackResult? =
            IntentApi.parseResult(resultCode, intent)
    }
}
