package tv.p2160.app

import android.app.Application
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import tv.p2160.app.handoff.Handoff
import tv.p2160.app.handoff.HandoffSendActivity
import tv.p2160.core.api.Player2160
import tv.p2160.core.api.PlayerAction

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Схема torrent:// для плеера (сам libtorrent запускается лениво).
        tv.p2160.torrent.TorrentEngine.install(this)

        // Передача между устройствами работает, пока приложение на экране (в т.ч. во время просмотра)
        // и пока идёт трансляция камеры — иначе другие устройства не найдут её, когда экран погашен.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = Handoff.start(this@App)
            override fun onStop(owner: LifecycleOwner) {
                if (!tv.p2160.app.camera.CameraStream.isStreaming) Handoff.stop()
            }
        })

        // Кнопка «Отправить на устройство» в плеере — через публичное API библиотеки.
        Player2160.registerAction(
            PlayerAction(id = "handoff", icon = Icons.Default.Devices, label = "handoff.send_title") { context, _ ->
                context.startActivity(Intent(context, HandoffSendActivity::class.java))
            }
        )
    }
}
