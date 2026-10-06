package tv.p2160.core.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
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

    class PlayContract : ActivityResultContract<PlaybackRequest, PlaybackResult?>() {
        override fun createIntent(context: Context, input: PlaybackRequest): Intent =
            intent(context, input.copy(returnResult = true))

        override fun parseResult(resultCode: Int, intent: Intent?): PlaybackResult? =
            IntentApi.parseResult(resultCode, intent)
    }
}
