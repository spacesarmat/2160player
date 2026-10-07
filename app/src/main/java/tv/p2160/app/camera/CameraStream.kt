package tv.p2160.app.camera

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.rtspserver.RtspServerStream
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.ServerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tv.p2160.app.handoff.Handoff

/** Камера устройства для выбора в трансляции. */
data class CameraInfo(
    val id: String,
    /** [CameraCharacteristics.LENS_FACING_BACK] / `FRONT` / `EXTERNAL`. */
    val facing: Int,
    /** Минимальное фокусное расстояние, мм (меньше — шире угол). */
    val focalLength: Float,
    /** Ключ подписи: `camera.back_main`, `camera.back_wide`, `camera.back_tele`, `camera.front`, `camera.external`. */
    val labelKey: String,
    /** Режимы, которые умеют и эта камера, и кодировщик устройства (от меньшего к большему). */
    val modes: List<StreamMode> = emptyList(),
    /** Есть вспышка — можно включить фонарик. */
    val hasFlash: Boolean = false,
)

/**
 * Режим трансляции: разрешение 16:9 и частота кадров. Список строится из возможностей устройства
 * ([CameraStream.cameras]): размеры кадра камеры, её предельная частота для каждого размера и что потянет
 * аппаратный кодировщик H.264.
 */
data class StreamMode(val width: Int, val height: Int, val fps: Int) {
    /** Битрейт видео: ~0,08 бит на пиксель кадра (1080p·30 — 5 Мбит/с, 4K·30 — 20 Мбит/с). */
    val bitrateKbps: Int get() = (width.toLong() * height * fps * 8 / 100 / 1000).toInt().coerceIn(1_000, 40_000)
    val label: String get() = "${if (height >= 2160) "4K" else "${height}p"} · $fps fps"

    companion object {
        val DEFAULT = StreamMode(1280, 720, 30)
    }
}

data class CameraStreamConfig(
    /** id камеры из [CameraStream.cameras]; null — основная задняя. */
    val cameraId: String? = null,
    val quality: StreamMode = StreamMode.DEFAULT,
    /** Звук с микрофона. */
    val audio: Boolean = true,
    val port: Int = CameraStream.DEFAULT_PORT,
)

sealed interface CameraStreamState {
    /** Нет трансляции (может идти предпросмотр). */
    data object Idle : CameraStreamState
    /** Сервер слушает [url]; [clients] — сколько зрителей подключено. */
    data class Streaming(val url: String, val clients: Int, val bitrateKbps: Int) : CameraStreamState
    data class Error(val message: String) : CameraStreamState
}

/**
 * Трансляция камеры телефона в сеть: RTSP-сервер на устройстве (`rtsp://<IP>:8554/`), видео H.264
 * (аппаратный кодировщик) и звук AAC с микрофона. Смотреть — в OBS («Источник медиа»), VLC или в другом
 * 2160 Player. Захват и кодирование — RootEncoder, сервер — RTSP-Server (pedroSG94, Apache-2.0).
 *
 * Предпросмотр работает и без трансляции (выбрать камеру и кадр). Сама трансляция идёт в
 * [CameraStreamService] (сервис переднего плана): экран можно закрыть или свернуть приложение.
 */
object CameraStream {
    const val DEFAULT_PORT = 8554
    private const val TAG = "CameraStream"

    private val _state = MutableStateFlow<CameraStreamState>(CameraStreamState.Idle)
    val state: StateFlow<CameraStreamState> = _state.asStateFlow()

    private val _config = MutableStateFlow(CameraStreamConfig())
    val config: StateFlow<CameraStreamConfig> = _config.asStateFlow()

    private var stream: RtspServerStream? = null

    private val _torch = MutableStateFlow(false)
    /** Фонарик (вспышка задней камеры) включён. */
    val torch: StateFlow<Boolean> = _torch.asStateFlow()

    /** Включить/выключить фонарик — только у камеры со вспышкой, пока камера работает. */
    fun setTorch(on: Boolean) {
        val source = stream?.videoSource as? Camera2Source ?: return
        runCatching { if (on) source.enableLantern() else source.disableLantern() }
        _torch.value = runCatching { source.isLanternEnabled() }.getOrDefault(false)
    }
    private var preparedFor: CameraStreamConfig? = null
    private var previewSurface: SurfaceView? = null

    val isStreaming: Boolean get() = stream?.isStreaming == true

    private val checker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() = Unit
        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "connection failed: $reason")
        }
        override fun onDisconnect() = Unit
        override fun onAuthError() = Unit
        override fun onAuthSuccess() = Unit
        override fun onNewBitrate(bitrate: Long) {
            _state.update { if (it is CameraStreamState.Streaming) it.copy(bitrateKbps = (bitrate / 1000).toInt()) else it }
        }
    }

    private val clients = object : ClientListener {
        override fun onClientConnected(client: ServerClient) = updateClients()
        override fun onClientDisconnected(client: ServerClient) = updateClients()
        override fun onClientNewBitrate(bitrate: Long, client: ServerClient) = Unit
    }

    private fun updateClients() {
        val n = stream?.getStreamClient()?.getNumClients() ?: 0
        _state.update { if (it is CameraStreamState.Streaming) it.copy(clients = n) else it }
    }

    /** Камеры устройства, задние — первыми (основная, широкоугольная, телевик), потом фронтальные. */
    fun cameras(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val caps = HashMap<String, Pair<List<StreamMode>, Boolean>>()
        val encoder = encoderCapabilities()
        val raw = runCatching {
            manager.cameraIdList.mapNotNull { id ->
                val c = manager.getCameraCharacteristics(id)
                val facing = c.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
                caps[id] = modesFor(c, encoder) to (c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
                Triple(id, facing, focal)
            }
        }.getOrDefault(emptyList())
        val backs = raw.filter { it.second == CameraCharacteristics.LENS_FACING_BACK }.sortedBy { it.third }
        // Основная задняя — первая в списке системы; по фокусному: короче неё — широкоугольная, длиннее — телевик.
        val main = raw.firstOrNull { it.second == CameraCharacteristics.LENS_FACING_BACK }
        return raw.map { (id, facing, focal) ->
            val key = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "camera.front"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "camera.external"
                else -> when {
                    main == null || id == main.first || backs.size < 2 -> "camera.back_main"
                    focal < main.third -> "camera.back_wide"
                    else -> "camera.back_tele"
                }
            }
            val (modes, flash) = caps[id] ?: (listOf(StreamMode.DEFAULT) to false)
            CameraInfo(id, facing, focal, key, modes.ifEmpty { listOf(StreamMode.DEFAULT) }, flash)
        }.sortedWith(compareBy({ if (it.facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1 }, { it.id != main?.first }, { it.focalLength }))
    }

    /**
     * Режимы камеры: кадры 16:9 из стандартного ряда (720p…4K), которые камера отдаёт на поверхность, × частоты
     * автоэкспозиции, которые она держит при этом размере (по минимальной длительности кадра), — и только те,
     * что потянет аппаратный кодировщик H.264 (в любой ориентации).
     */
    private fun modesFor(c: CameraCharacteristics, encoder: List<android.media.MediaCodecInfo.VideoCapabilities>): List<StreamMode> {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        val target = android.graphics.SurfaceTexture::class.java
        val sizes = map.getOutputSizes(target)?.toList().orEmpty()
        val aeFps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.map { it.upper }?.filter { it in 24..120 }?.distinct()?.sorted().orEmpty().ifEmpty { listOf(30) }
        return STANDARD_HEIGHTS.flatMap { h ->
            val w = h * 16 / 9
            val size = sizes.firstOrNull { it.width == w && it.height == h } ?: return@flatMap emptyList()
            val minFrameNs = runCatching { map.getOutputMinFrameDuration(target, size) }.getOrDefault(0L)
            val sizeMaxFps = if (minFrameNs > 0) (1_000_000_000.0 / minFrameNs + 0.5).toInt() else aeFps.last()
            aeFps.filter { it <= sizeMaxFps && it in STANDARD_FPS }
                .filter { fps -> encoder.isEmpty() || encoder.any { e -> e.supports(w, h, fps) || e.supports(h, w, fps) } }
                .map { StreamMode(w, h, it) }
        }
    }

    private fun android.media.MediaCodecInfo.VideoCapabilities.supports(w: Int, h: Int, fps: Int): Boolean =
        runCatching { areSizeAndRateSupported(w, h, fps.toDouble()) }.getOrDefault(false)

    /** Возможности аппаратных кодировщиков H.264 (программные — только если других нет). */
    private fun encoderCapabilities(): List<android.media.MediaCodecInfo.VideoCapabilities> {
        val all = runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
        }.getOrDefault(emptyList())
        val hw = all.filter { info ->
            if (android.os.Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
            else !info.name.startsWith("OMX.google.", true) && !info.name.startsWith("c2.android.", true)
        }
        return (hw.ifEmpty { all }).mapNotNull { runCatching { it.getCapabilitiesForType("video/avc").videoCapabilities }.getOrNull() }
    }

    private val STANDARD_HEIGHTS = listOf(720, 1080, 1440, 2160)
    private val STANDARD_FPS = setOf(24, 25, 30, 48, 50, 60, 90, 120)

    /**
     * Сменить настройки. Камеру можно переключить и во время трансляции; качество и звук — только без
     * неё (кодировщик пересоздаётся).
     */
    fun update(context: Context, transform: (CameraStreamConfig) -> CameraStreamConfig) {
        val old = _config.value
        val next = transform(old)
        if (next == old) return
        if (isStreaming && (next.quality != old.quality || next.audio != old.audio || next.port != old.port)) return
        _config.value = next
        val s = stream
        if (s != null && next.cameraId != old.cameraId && next.copy(cameraId = old.cameraId) == old) {
            _torch.value = false
            next.cameraId?.let { id -> (s.videoSource as? Camera2Source)?.openCameraId(id) }
            preparedFor = next
            return
        }
        if (!isStreaming) {
            val surface = previewSurface
            release()
            surface?.let { startPreview(context, it) }
        }
    }

    private fun ensurePrepared(context: Context): RtspServerStream? {
        val cfg = _config.value
        stream?.takeIf { preparedFor == cfg }?.let { return it }
        release()
        val app = context.applicationContext
        val audio = cfg.audio && ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val s = RtspServerStream(app, cfg.port, checker, Camera2Source(app), if (audio) MicrophoneSource() else NoAudioSource())
        s.getGlInterface().autoHandleOrientation = true
        val rotation = CameraHelper.getCameraOrientation(app)
        val q = cfg.quality
        val ok = runCatching {
            s.prepareVideo(q.width, q.height, q.bitrateKbps * 1000, q.fps, iFrameInterval = 2, rotation = rotation) &&
                s.prepareAudio(44_100, true, 128_000)
        }.getOrElse { e ->
            Log.w(TAG, "prepare failed", e)
            false
        }
        if (!ok) {
            runCatching { s.release() }
            _state.value = CameraStreamState.Error("prepare")
            return null
        }
        s.getStreamClient().setClientListener(clients)
        // Адрес в ответах сервера (Content-Base) — IPv4: по нему OBS/ffmpeg шлют SETUP, а подключаются по IPv4.
        s.getStreamClient().forceIpType(com.pedro.rtspserver.server.IpType.IPv4)
        // Библиотека по умолчанию пишет в лог каждый пакет — это лишняя нагрузка на процессор.
        s.getStreamClient().setLogs(false)
        stream = s
        preparedFor = cfg
        if (_state.value is CameraStreamState.Error) _state.value = CameraStreamState.Idle
        return s
    }

    /** Показать камеру на [surface] (вызывать, когда поверхность создана). */
    fun startPreview(context: Context, surface: SurfaceView) {
        previewSurface = surface
        val s = ensurePrepared(context) ?: return
        if (!s.isOnPreview) runCatching { s.startPreview(surface) }.onFailure { Log.w(TAG, "preview", it) }
        _config.value.cameraId?.let { id -> (s.videoSource as? Camera2Source)?.takeIf { it.getCurrentCameraId() != id }?.openCameraId(id) }
    }

    /** Поверхность исчезла (экран закрыт): предпросмотр выключаем, трансляция продолжается. */
    fun stopPreview(surface: SurfaceView) {
        if (previewSurface === surface) previewSurface = null
        stream?.takeIf { it.isOnPreview }?.let { runCatching { it.stopPreview() } }
        if (!isStreaming) release()
    }

    /** Начать трансляцию: сервис переднего плана держит камеру и сеть, пока экран закрыт. */
    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, CameraStreamService::class.java))
    }

    /** Вызывается сервисом после startForeground. */
    internal fun startStreaming(context: Context): Boolean {
        val s = ensurePrepared(context) ?: return false
        if (s.isStreaming) return true
        return runCatching {
            s.startStream()
            _config.value.cameraId?.let { id -> (s.videoSource as? Camera2Source)?.takeIf { it.getCurrentCameraId() != id }?.openCameraId(id) }
            _state.value = CameraStreamState.Streaming(url(context), clients = 0, bitrateKbps = 0)
            true
        }.getOrElse { e ->
            Log.w(TAG, "start failed", e)
            _state.value = CameraStreamState.Error(e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /** Остановить трансляцию (и сервис). */
    fun stop(context: Context) {
        context.stopService(Intent(context, CameraStreamService::class.java))
        stopStreaming()
    }

    internal fun stopStreaming() {
        stream?.takeIf { it.isStreaming }?.let { runCatching { it.stopStream() } }
        if (_state.value is CameraStreamState.Streaming) _state.value = CameraStreamState.Idle
        if (previewSurface == null) release()
    }

    private fun release() {
        _torch.value = false
        stream?.let { s ->
            runCatching { if (s.isStreaming) s.stopStream() }
            runCatching { if (s.isOnPreview) s.stopPreview() }
            runCatching { s.release() }
        }
        stream = null
        preparedFor = null
    }

    /** Адрес для зрителей: IPv4 в локальной сети (как у передачи между устройствами), иначе — что сообщит сервер. */
    private fun url(context: Context): String {
        val host = Handoff.baseUrl()?.let { Uri.parse(it).host }
        if (host != null) return "rtsp://$host:${_config.value.port}/"
        return stream?.getStreamClient()?.getEndPointConnection() ?: "rtsp://?:${_config.value.port}/"
    }
}
