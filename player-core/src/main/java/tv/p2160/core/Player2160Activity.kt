package tv.p2160.core

import android.app.Application
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import tv.p2160.core.api.IntentApi
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.engine.PlayerController
import tv.p2160.core.settings.PlayerSettings
import tv.p2160.core.ui.PlayerScreen

/** Держит контроллер между поворотами экрана и пересозданиями Activity. */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {
    var controller by mutableStateOf<PlayerController?>(null)
        private set
    var request: PlaybackRequest? = null
        private set

    fun open(request: PlaybackRequest) {
        controller?.release()
        this.request = request
        controller = PlayerController(getApplication(), request)
    }

    override fun onCleared() {
        controller?.release()
        controller = null
    }
}

/**
 * Полноэкранный плеер. Принимает Intent'ы в формате [IntentApi] (совместим с MX Player / VLC)
 * и, если вызван через startActivityForResult, возвращает позицию остановки.
 */
class Player2160Activity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()
    private var inPip by mutableStateOf(false)

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.controller?.addExternalSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        if (vm.controller == null) {
            val request = IntentApi.parse(intent) ?: run { finish(); return }
            vm.open(request)
        }

        setContent {
            vm.controller?.let { controller ->
                PlayerScreen(
                    controller = controller,
                    settingsStore = PlayerSettings.get(this),
                    onBack = ::finishWithResult,
                    onPickSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
                    inPictureInPicture = inPip,
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.controller?.state?.collect { s -> adjustOrientation(s.hasVideo, s.videoAspect) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IntentApi.parse(intent)?.let(vm::open)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = vm.controller ?: return
        val s = c.state.value
        if (s.isPlaying && s.hasVideo && supportsPip()) {
            val aspect = s.videoAspect.takeIf { it > 0 }?.coerceIn(0.42f, 2.39f) ?: (16f / 9f)
            runCatching {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(Rational((aspect * 1000).toInt(), 1000))
                        .build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // В PiP продолжаем играть; закрытие окна PiP тоже приходит сюда — тогда ставим на паузу.
        val controller = vm.controller ?: return
        if (!inPip || isFinishing) controller.player.pause()
        controller.saveProgress()
    }

    private fun finishWithResult() {
        val controller = vm.controller
        val request = vm.request
        if (controller != null && (request?.returnResult == true || callingActivity != null)) {
            controller.saveProgress()
            setResult(RESULT_OK, IntentApi.buildResult(controller.result()))
        }
        finish()
    }

    private fun adjustOrientation(hasVideo: Boolean, aspect: Float) {
        if (isTv() || !hasVideo || aspect <= 0f) return
        val wanted = if (aspect < 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (requestedOrientation != wanted) requestedOrientation = wanted
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun isTv(): Boolean =
        (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}
