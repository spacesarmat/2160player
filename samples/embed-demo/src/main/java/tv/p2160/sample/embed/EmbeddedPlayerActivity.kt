package tv.p2160.sample.embed

import android.app.Application
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.Player2160
import tv.p2160.core.engine.PlayerController
import tv.p2160.core.ui.PlayerScreen

/**
 * Хранит [PlayerController] между поворотами экрана: контроллер создаётся один раз
 * и освобождается в [onCleared], когда Activity закрывается окончательно.
 */
class EmbeddedPlayerViewModel(app: Application) : AndroidViewModel(app) {
    var controller by mutableStateOf<PlayerController?>(null)
        private set

    fun open(request: PlaybackRequest) {
        controller?.release()
        controller = PlayerController(getApplication(), request)
    }

    override fun onCleared() {
        controller?.release()
        controller = null
    }
}

/**
 * Пример встраивания Compose-экрана [PlayerScreen] в собственную Activity:
 * сверху — своя панель приложения, под ней — плеер библиотеки.
 */
class EmbeddedPlayerActivity : ComponentActivity() {

    private val vm: EmbeddedPlayerViewModel by viewModels()
    private var inPip by mutableStateOf(false)

    // Кнопка «Загрузить из файла…» в панели субтитров вызывает onPickSubtitle — выбор файла за нами.
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.controller?.addExternalSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (vm.controller == null) {
            val uri = intent.data ?: run { finish(); return }
            vm.open(PlaybackRequest.single(uri, intent.getStringExtra(EXTRA_TITLE)))
        }

        setContent {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                if (!inPip) {
                    Text(
                        "Плеер встроен в экран приложения-хоста",
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E3A8A))
                            .safeDrawingPadding()
                            .padding(12.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    vm.controller?.let { controller ->
                        PlayerScreen(
                            controller = controller,
                            settingsStore = Player2160.settings(this@EmbeddedPlayerActivity),
                            onBack = ::finish,
                            onPickSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
                            inPictureInPicture = inPip,
                        )
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Нажали «Домой» во время просмотра — уходим в картинку-в-картинке.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val state = vm.controller?.state?.value ?: return
        if (!state.isPlaying || !state.hasVideo) return
        val aspect = state.videoAspect.takeIf { it > 0f }?.coerceIn(0.42f, 2.39f) ?: (16f / 9f)
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
                    .build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        vm.controller?.setInBackground(false)   // снова декодируем видео
    }

    override fun onStop() {
        super.onStop()
        val controller = vm.controller ?: return
        controller.saveProgress()
        if (isFinishing) { controller.player.pause(); return }
        if (inPip && !isChangingConfigurations) return            // в PiP играем дальше
        // Аудио — если «Музыка и аудио в фоне», видео — если «Видео в фоне»; иначе пауза.
        val s = controller.state.value
        val settings = Player2160.settings(this).current
        val background = controller.player.playWhenReady &&
            (if (s.hasVideo) settings.backgroundPlayback else settings.backgroundAudio)
        if (background) controller.setInBackground(true) else controller.player.pause()
    }

    companion object {
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, uri: Uri, title: String?): Intent =
            Intent(context, EmbeddedPlayerActivity::class.java)
                .setData(uri)
                .putExtra(EXTRA_TITLE, title)
    }
}
