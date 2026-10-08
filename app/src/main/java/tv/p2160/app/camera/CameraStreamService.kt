package tv.p2160.app.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tv.p2160.app.MainActivity
import tv.p2160.app.R
import tv.p2160.core.i18n.I18n

/**
 * Сервис переднего плана для трансляции камеры: Android не даёт пользоваться камерой и микрофоном в фоне
 * без него. Уведомление показывает адрес и кнопку «Остановить».
 */
class CameraStreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CameraStream.stop(this)
            return START_NOT_STICKY
        }
        val mic = CameraStream.config.value.audio &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val type = if (Build.VERSION.SDK_INT >= 30) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(null), type) }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!CameraStream.startStreaming(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Адрес в уведомлении.
        scope.launch {
            CameraStream.state.collect { s ->
                if (s is CameraStreamState.Streaming) {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(s))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        CameraStream.stopStreaming()
        super.onDestroy()
    }

    private fun notification(s: CameraStreamState.Streaming?): Notification {
        val strings = I18n.get(this).current
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, strings["camera.channel"], NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CAMERA, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags,
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraStreamService::class.java).setAction(ACTION_STOP), flags)
        val text = when {
            s == null -> strings["camera.starting"]
            s.protocol == StreamProtocol.RTSP -> strings.format("camera.notification_text", s.url, s.clients)
            else -> strings.format("camera.notification_push", s.url, strings[if (s.connected) "camera.push_connected" else "camera.push_connecting"])
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(strings["camera.notification_title"])
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, strings["camera.stop"], stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "camera_stream"
        private const val NOTIFICATION_ID = 2160_7
        private const val ACTION_STOP = "tv.p2160.app.camera.STOP"

        fun stopIntent(context: Context) = Intent(context, CameraStreamService::class.java).setAction(ACTION_STOP)
    }
}
