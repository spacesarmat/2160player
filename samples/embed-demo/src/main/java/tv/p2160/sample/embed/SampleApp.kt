package tv.p2160.sample.embed

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import tv.p2160.core.api.NowPlaying
import tv.p2160.core.api.Player2160
import tv.p2160.core.api.PlayerAction

class SampleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Кнопки регистрируются глобально (на процесс) — удобнее всего в Application.onCreate.
        Player2160.registerAction(
            PlayerAction(
                id = SHARE_ACTION_ID,
                icon = Icons.Default.Share,
                // Ключ локализации или готовая строка: если ключа нет в пакетах, показывается как есть.
                label = "Поделиться ссылкой",
                onClick = ::shareLink,
            )
        )
    }

    private fun shareLink(context: Context, nowPlaying: NowPlaying?) {
        val uri = nowPlaying?.uri ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, nowPlaying.title)
            .putExtra(Intent.EXTRA_TEXT, uri.toString())
        val chooser = Intent.createChooser(send, null)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    companion object {
        const val SHARE_ACTION_ID = "sample.share_link"
    }
}
