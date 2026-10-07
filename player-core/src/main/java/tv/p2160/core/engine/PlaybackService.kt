package tv.p2160.core.engine

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import tv.p2160.core.Player2160Activity

/**
 * Текущее воспроизведение для системы: MediaSession (уведомление, экран блокировки, гарнитура,
 * Bluetooth, часы) и сервис, который держит процесс живым, пока звук играет в фоне.
 * Один активный [PlayerController] на процесс; вызывается с главного потока.
 */
@OptIn(UnstableApi::class)
internal object PlaybackSessions {
    var controller: PlayerController? = null
        private set
    var session: MediaSession? = null
        private set
    private var nextId = 0

    fun attach(context: Context, controller: PlayerController) {
        this.controller?.let { detach(context, it) }
        val app = context.applicationContext
        val open = Intent(app, Player2160Activity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        session = MediaSession.Builder(app, controller.player)
            .setId("p2160-${nextId++}")
            .setSessionActivity(PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        this.controller = controller
        // Плеер создаётся, пока приложение на экране, — запуск сервиса разрешён.
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }

    fun detach(context: Context, controller: PlayerController) {
        if (this.controller !== controller) return
        session?.release()
        session = null
        this.controller = null
        runCatching { context.applicationContext.stopService(Intent(context.applicationContext, PlaybackService::class.java)) }
    }

    /** Пауза активного плеера с любого потока (например, после «Продолжить на другом устройстве»). */
    fun pause() {
        Handler(Looper.getMainLooper()).post { controller?.player?.pause() }
    }
}

/** Сервис медиасессии: показывает уведомление с управлением и держит фоновое воспроизведение. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = PlaybackSessions.session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PlaybackSessions.session?.let { if (!isSessionAdded(it)) addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlaybackSessions.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }
}
