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
import com.pedro.library.base.StreamBase
import com.pedro.library.rtmp.RtmpStream
import com.pedro.library.srt.SrtStream
import com.pedro.rtspserver.RtspServerStream
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.ServerClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.p2160.app.handoff.Handoff
import tv.p2160.app.handoff.HandoffAuth

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

/** Что транслируем. */
enum class StreamSource { CAMERA, SCREEN }

/** Звук трансляции экрана. */
enum class ScreenAudio {
    /** С микрофона. */
    MIC,
    /** Звук телефона (Android 10+; приложения могут его запрещать). */
    DEVICE,
    /** Звук телефона и микрофон вместе. */
    BOTH,
}

/** Микрофон устройства: встроенный, гарнитура, USB, Bluetooth. */
data class MicInfo(val id: Int, val labelKey: String, val name: String)

/**
 * Усиление микрофона и замер уровня одним эффектом (у микрофона RootEncoder — один эффект).
 * PCM 16 бит: умножаем с ограничением и считаем пик ~10 раз в секунду.
 */
class GainMeterEffect : com.pedro.encoder.input.audio.CustomAudioEffect() {
    @Volatile var gain: Float = 1f
    private val _level = MutableStateFlow(0f)
    /** Уровень звука 0..1 (пик за последние ~0,1 с, после усиления). */
    val level: StateFlow<Float> = _level.asStateFlow()
    private var peak = 0
    private var samples = 0

    override fun process(pcmBuffer: ByteArray): ByteArray {
        val g = gain
        var i = 0
        while (i + 1 < pcmBuffer.size) {
            var v = (pcmBuffer[i].toInt() and 0xFF) or (pcmBuffer[i + 1].toInt() shl 8)
            if (g != 1f) {
                v = (v * g).toInt().coerceIn(-32768, 32767)
                pcmBuffer[i] = (v and 0xFF).toByte()
                pcmBuffer[i + 1] = (v shr 8).toByte()
            }
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            i += 2
        }
        samples += pcmBuffer.size / 2
        if (samples >= 8_820) { // ~0,1 с при 44,1 кГц стерео
            _level.value = peak / 32768f
            peak = 0
            samples = 0
        }
        return pcmBuffer
    }

    fun reset() {
        _level.value = 0f
        peak = 0
        samples = 0
    }
}

/** Куда идёт трансляция. */
enum class StreamProtocol {
    /** RTSP-сервер на телефоне: зрители (OBS, VLC, 2160 Player) подключаются сами. */
    RTSP,
    /** Телефон сам отправляет поток по SRT (OBS в режиме listener, медиасервер). */
    SRT,
    /** Телефон отправляет поток по RTMP на сервис (YouTube, Twitch, свой сервер). */
    RTMP,
}

data class CameraStreamConfig(
    /** id камеры из [CameraStream.cameras]; null — основная задняя. */
    val cameraId: String? = null,
    /** По умолчанию 1080p·30; если камера его не умеет — экран выберет ближайший (720p·30 — запасной [StreamMode.DEFAULT]). */
    val quality: StreamMode = StreamMode(1920, 1080, 30),
    /** Звук с микрофона. */
    val audio: Boolean = true,
    val port: Int = CameraStream.DEFAULT_PORT,
    val protocol: StreamProtocol = StreamProtocol.RTSP,
    /** Куда слать SRT: `srt://IP-компьютера:9000` (параметры `?…` — по желанию). */
    val srtUrl: String = "",
    /** Сервер RTMP (`rtmp://a.rtmp.youtube.com/live2`) и ключ трансляции (хранится только на устройстве). */
    val rtmpUrl: String = "",
    val rtmpKey: String = "",
    val source: StreamSource = StreamSource.CAMERA,
    /** Звук при трансляции экрана (если включён [audio]). */
    val screenAudio: ScreenAudio = ScreenAudio.MIC,
    /** Микрофон ([MicInfo.id]); null — выбирает система. */
    val micId: Int? = null,
    /** Усиление микрофона, 0,5–4. */
    val micGain: Float = 1f,
)

sealed interface CameraStreamState {
    /** Нет трансляции (может идти предпросмотр). */
    data object Idle : CameraStreamState
    /**
     * Трансляция идёт. RTSP: сервер слушает [url], [clients] — зрителей. SRT/RTMP: [url] — куда отправляем
     * (без ключа), [connected] — соединение установлено (до этого — подключение/повтор).
     */
    data class Streaming(
        val url: String,
        val clients: Int,
        val bitrateKbps: Int,
        val protocol: StreamProtocol = StreamProtocol.RTSP,
        val connected: Boolean = true,
        /** RTSP с защитой: пароль (логин [CameraStream.RTSP_USER]); null — поток открыт. */
        val password: String? = null,
    ) : CameraStreamState {
        /** Адрес с логином и паролем — для OBS/VLC и сопряжённых плееров. */
        val urlWithAuth: String
            get() = if (password == null || !url.startsWith("rtsp://")) url
            else "rtsp://${CameraStream.RTSP_USER}:$password@" + url.removePrefix("rtsp://")
    }
    /** [message] — ключ строки (`camera.err_*`) или текст ошибки соединения. */
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
    /** Логин RTSP при защите; пароль — код «Передачи между устройствами» ([HandoffAuth.currentCode]). */
    const val RTSP_USER = "2160"
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var authJob: kotlinx.coroutines.Job? = null
    private const val TAG = "CameraStream"

    private val _state = MutableStateFlow<CameraStreamState>(CameraStreamState.Idle)
    val state: StateFlow<CameraStreamState> = _state.asStateFlow()

    private val _config = MutableStateFlow(CameraStreamConfig())
    val config: StateFlow<CameraStreamConfig> = _config.asStateFlow()

    private var stream: StreamBase? = null
    private var appContext: Context? = null
    private var loaded = false

    private val _torch = MutableStateFlow(false)
    /** Фонарик (вспышка задней камеры) включён. */
    val torch: StateFlow<Boolean> = _torch.asStateFlow()

    private val _zoom = MutableStateFlow(1f)
    /** Текущий зум (1 — без увеличения). */
    val zoom: StateFlow<Float> = _zoom.asStateFlow()

    /** Диапазон зума работающей камеры (у широкоугольной может начинаться ниже 1). */
    fun zoomRange(): ClosedFloatingPointRange<Float> {
        val r = (stream?.videoSource as? Camera2Source)?.getZoomRange() ?: return 1f..1f
        return r.lower..r.upper
    }

    fun setZoom(level: Float) {
        val source = stream?.videoSource as? Camera2Source ?: return
        val r = zoomRange()
        runCatching { source.setZoom(level.coerceIn(r.start, r.endInclusive)) }
        _zoom.value = runCatching { source.getZoom() }.getOrDefault(level)
    }

    /** Жест на предпросмотре: два пальца — зум щипком. */
    fun onPreviewTouch(view: android.view.View, event: android.view.MotionEvent) {
        val source = stream?.videoSource as? Camera2Source ?: return
        if (event.pointerCount >= 2) {
            runCatching { source.setZoom(event) }
            _zoom.value = runCatching { source.getZoom() }.getOrDefault(_zoom.value)
        }
    }

    /** Касание предпросмотра: фокус (и экспозиция) в этой точке. */
    fun tapToFocus(view: android.view.View, event: android.view.MotionEvent): Boolean {
        val source = stream?.videoSource as? Camera2Source ?: return false
        return runCatching { source.tapToFocus(view, event) }.getOrDefault(false)
    }

    /** Вернуть непрерывный автофокус. */
    fun autoFocus() {
        (stream?.videoSource as? Camera2Source)?.let { runCatching { it.enableAutoFocus() } }
    }

    /** Включить/выключить фонарик — только у камеры со вспышкой, пока камера работает. */
    fun setTorch(on: Boolean) {
        val source = stream?.videoSource as? Camera2Source ?: return
        runCatching { if (on) source.enableLantern() else source.disableLantern() }
        _torch.value = runCatching { source.isLanternEnabled() }.getOrDefault(false)
    }
    private var preparedFor: CameraStreamConfig? = null
    private var previewSurface: SurfaceView? = null

    val isStreaming: Boolean get() = stream?.isStreaming == true && _state.value is CameraStreamState.Streaming

    /** Идёт трансляция через RTSP-сервер на этом устройстве — её можно смотреть с других (`/camera`, mDNS `cam=1`). */
    val isServing: Boolean get() = isStreaming && (_state.value as? CameraStreamState.Streaming)?.protocol == StreamProtocol.RTSP

    private fun prefs(context: Context) = context.getSharedPreferences("p2160_camera", Context.MODE_PRIVATE)

    /** Загрузить сохранённые настройки (один раз за процесс). */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        appContext = context.applicationContext
        val p = prefs(context)
        val d = CameraStreamConfig()
        _config.value = CameraStreamConfig(
            cameraId = p.getString("camera", null),
            quality = StreamMode(p.getInt("w", d.quality.width), p.getInt("h", d.quality.height), p.getInt("fps", d.quality.fps)),
            audio = p.getBoolean("audio", d.audio),
            protocol = runCatching { StreamProtocol.valueOf(p.getString("protocol", null)!!) }.getOrDefault(d.protocol),
            srtUrl = p.getString("srt_url", "").orEmpty(),
            rtmpUrl = p.getString("rtmp_url", "").orEmpty(),
            rtmpKey = p.getString("rtmp_key", "").orEmpty(),
            source = runCatching { StreamSource.valueOf(p.getString("source", null)!!) }.getOrDefault(d.source),
            screenAudio = runCatching { ScreenAudio.valueOf(p.getString("screen_audio", null)!!) }.getOrDefault(d.screenAudio),
            micId = p.getInt("mic_id", -1).takeIf { it >= 0 },
            micGain = p.getFloat("mic_gain", d.micGain),
        )
    }

    private fun save(context: Context, c: CameraStreamConfig) {
        prefs(context).edit()
            .putString("camera", c.cameraId)
            .putInt("w", c.quality.width).putInt("h", c.quality.height).putInt("fps", c.quality.fps)
            .putBoolean("audio", c.audio)
            .putString("protocol", c.protocol.name)
            .putString("srt_url", c.srtUrl)
            .putString("rtmp_url", c.rtmpUrl)
            .putString("rtmp_key", c.rtmpKey)
            .putString("source", c.source.name)
            .putString("screen_audio", c.screenAudio.name)
            .putInt("mic_id", c.micId ?: -1)
            .putFloat("mic_gain", c.micGain)
            .apply()
    }

    private val checker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() {
            _state.update { if (it is CameraStreamState.Streaming) it.copy(connected = true) else it }
        }
        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "connection failed: $reason")
            val s = stream ?: return
            if (s is RtspServerStream) return
            // SRT/RTMP: сеть моргнула или приёмник ещё не запущен — повторяем; кончились попытки — ошибка.
            _state.update { if (it is CameraStreamState.Streaming) it.copy(connected = false) else it }
            if (!s.getStreamClient().reTry(5_000, reason)) {
                appContext?.let { stop(it) }
                _state.value = CameraStreamState.Error(reason)
            }
        }
        override fun onDisconnect() {
            _state.update { if (it is CameraStreamState.Streaming && it.protocol != StreamProtocol.RTSP) it.copy(connected = false) else it }
        }
        override fun onAuthError() {
            appContext?.let { stop(it) }
            _state.value = CameraStreamState.Error("camera.err_auth")
        }
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
        val n = (stream as? RtspServerStream)?.getStreamClient()?.getNumClients() ?: 0
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
        if (isStreaming && (next.quality != old.quality || next.audio != old.audio || next.port != old.port || next.protocol != old.protocol ||
                next.source != old.source || next.screenAudio != old.screenAudio)) return
        _config.value = next
        save(context, next)
        // Усиление и микрофон — на лету, без пересоздания кодировщиков.
        gainMeter.gain = next.micGain
        if (next.micId != old.micId) applyMic(context, next.micId)
        // Адрес/ключ влияют только на старт трансляции — камеру не пересоздаём.
        if (next.copy(srtUrl = old.srtUrl, rtmpUrl = old.rtmpUrl, rtmpKey = old.rtmpKey, micId = old.micId, micGain = old.micGain) == old) return
        val s = stream
        if (s != null && next.cameraId != old.cameraId && next.copy(cameraId = old.cameraId, srtUrl = old.srtUrl, rtmpUrl = old.rtmpUrl, rtmpKey = old.rtmpKey) == old) {
            _torch.value = false
            _zoom.value = 1f
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

    /** Камера и кодировщики готовы для этих настроек (адрес и ключ не в счёт — они нужны только при старте). */
    private fun sameEncoder(a: CameraStreamConfig?, b: CameraStreamConfig) =
        a != null && a.copy(srtUrl = "", rtmpUrl = "", rtmpKey = "", micId = null, micGain = 1f) ==
            b.copy(srtUrl = "", rtmpUrl = "", rtmpKey = "", micId = null, micGain = 1f)

    /** Разрешение на запись экрана (MediaProjection) для трансляции экрана; живёт до остановки трансляции. */
    private var projection: android.media.projection.MediaProjection? = null

    /** Пользователь остановил запись экрана системной кнопкой — останавливаем трансляцию. */
    private val projectionCallback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() {
            scope.launch { appContext?.let { stop(it) } }
        }
    }

    /** Сервис получил разрешение на запись экрана — дальше [startStreaming] возьмёт экран вместо камеры. */
    internal fun setProjection(p: android.media.projection.MediaProjection?) {
        projection?.takeIf { it !== p }?.let { runCatching { it.stop() } }
        projection = p
        p?.registerCallback(projectionCallback, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    /** Режимы для экрана: стандартные кадры 16:9, которые потянет аппаратный кодировщик. */
    fun screenModes(): List<StreamMode> {
        val encoder = encoderCapabilities()
        return listOf(StreamMode(1280, 720, 30), StreamMode(1280, 720, 60), StreamMode(1920, 1080, 30), StreamMode(1920, 1080, 60))
            .filter { m -> encoder.isEmpty() || encoder.any { it.supports(m.width, m.height, m.fps) } }
            .ifEmpty { listOf(StreamMode.DEFAULT) }
    }

    private fun ensurePrepared(context: Context): StreamBase? {
        val cfg = _config.value
        stream?.takeIf { sameEncoder(preparedFor, cfg) }?.let { return it }
        release()
        val app = context.applicationContext
        appContext = app
        val mic = cfg.audio && ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val screen = if (cfg.source == StreamSource.SCREEN) projection ?: run {
            _state.value = CameraStreamState.Error("camera.err_screen")
            return null
        } else null
        val videoSource = if (screen != null) com.pedro.encoder.input.sources.video.ScreenSource(app, screen) else Camera2Source(app)
        val audioSource = when {
            !cfg.audio -> NoAudioSource()
            screen != null && android.os.Build.VERSION.SDK_INT >= 29 && cfg.screenAudio == ScreenAudio.DEVICE ->
                com.pedro.encoder.input.sources.audio.InternalAudioSource(screen)
            screen != null && android.os.Build.VERSION.SDK_INT >= 29 && cfg.screenAudio == ScreenAudio.BOTH && mic ->
                com.pedro.encoder.input.sources.audio.MixAudioSource(screen)
            mic -> MicrophoneSource().also { m ->
                gainMeter.gain = cfg.micGain
                gainMeter.reset()
                m.setAudioEffect(gainMeter)
                if (android.os.Build.VERSION.SDK_INT >= 23) m.setPreferredDevice(micDevice(app, cfg.micId))
            }
            else -> NoAudioSource()
        }
        val s: StreamBase = when (cfg.protocol) {
            StreamProtocol.RTSP -> RtspServerStream(app, cfg.port, checker, videoSource, audioSource)
            StreamProtocol.SRT -> SrtStream(app, checker, videoSource, audioSource)
            StreamProtocol.RTMP -> RtmpStream(app, checker, videoSource, audioSource)
        }
        s.getGlInterface().autoHandleOrientation = true
        // Экран отдаёт кадр, только когда картинка меняется: без повтора поток «застывает», и плееры уходят
        // в буферизацию. Повторяем последний кадр не реже 15 раз в секунду (как в примере RootEncoder).
        if (screen != null) s.getGlInterface().setForceRender(true, 15)
        // Кадр всегда горизонтальный 16:9 (ТВ, OBS): телефон вертикально — картинка с полями по бокам,
        // и поворот во время трансляции ничего не ломает.
        val rotation = 0
        val q = cfg.quality
        val ok = runCatching {
            s.prepareVideo(q.width, q.height, q.bitrateKbps * 1000, q.fps, iFrameInterval = 1, rotation = rotation) &&
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
        if (s is RtspServerStream) {
            s.getStreamClient().setClientListener(clients)
            // Защита как у «Передачи между устройствами»: включена — поток по логину 2160 и текущему коду.
            s.getStreamClient().setAuthorization(RTSP_USER.takeIf { HandoffAuth.required }, rtspPassword())
            // Адрес в ответах сервера (Content-Base) — IPv4: по нему OBS/ffmpeg шлют SETUP, а подключаются по IPv4.
            s.getStreamClient().forceIpType(com.pedro.rtspserver.server.IpType.IPv4)
        } else {
            // Приёмник (OBS, сервис) может быть ещё не запущен или сеть моргнуть — повторяем подключение.
            s.getStreamClient().setReTries(10)
        }
        // Библиотека по умолчанию пишет в лог каждый пакет — это лишняя нагрузка на процессор.
        s.getStreamClient().setLogs(false)
        stream = s
        preparedFor = cfg
        if (_state.value is CameraStreamState.Error) _state.value = CameraStreamState.Idle
        return s
    }

    /** Показать камеру на [surface] (вызывать, когда поверхность создана). Для экрана предпросмотра нет. */
    fun startPreview(context: Context, surface: SurfaceView) {
        previewSurface = surface
        if (_config.value.source == StreamSource.SCREEN) return
        val s = ensurePrepared(context) ?: return
        if (!s.isOnPreview) runCatching { s.startPreview(surface) }.onFailure { Log.w(TAG, "preview", it) }
        _config.value.cameraId?.let { id -> (s.videoSource as? Camera2Source)?.takeIf { it.getCurrentCameraId() != id }?.openCameraId(id) }
    }

    /** Размер окна предпросмотра изменился. */
    fun setPreviewSize(width: Int, height: Int) {
        stream?.getGlInterface()?.setPreviewResolution(width, height)
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

    /** Начать трансляцию экрана: [resultCode]/[data] — ответ на системный запрос записи экрана. */
    fun startScreen(context: Context, resultCode: Int, data: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CameraStreamService::class.java)
                .putExtra(CameraStreamService.EXTRA_PROJECTION_CODE, resultCode)
                .putExtra(CameraStreamService.EXTRA_PROJECTION, data),
        )
    }

    /** Вызывается сервисом после startForeground. */
    internal fun startStreaming(context: Context): Boolean {
        val cfg = _config.value
        // Куда отправлять: адрес проверяем до запуска камеры.
        val target = when (cfg.protocol) {
            StreamProtocol.RTSP -> ""
            StreamProtocol.SRT -> cfg.srtUrl.trim().takeIf { it.startsWith("srt://", true) && it.length > 6 }
            StreamProtocol.RTMP -> rtmpEndpoint(cfg.rtmpUrl, cfg.rtmpKey)
        }
        if (target == null) {
            _state.value = CameraStreamState.Error(if (cfg.protocol == StreamProtocol.SRT) "camera.err_srt_url" else "camera.err_rtmp_url")
            return false
        }
        val s = ensurePrepared(context) ?: return false
        if (s.isStreaming) return true
        return runCatching {
            // Состояние — до старта: SRT/RTMP могут сообщить «подключено» раньше, чем вернётся startStream.
            _state.value = when (cfg.protocol) {
                StreamProtocol.RTSP -> CameraStreamState.Streaming(url(context), clients = 0, bitrateKbps = 0, password = rtspPassword())
                // Ключ RTMP на экран и в уведомление не выводим.
                StreamProtocol.SRT -> CameraStreamState.Streaming(target, 0, 0, StreamProtocol.SRT, connected = false)
                StreamProtocol.RTMP -> CameraStreamState.Streaming(cfg.rtmpUrl.trim(), 0, 0, StreamProtocol.RTMP, connected = false)
            }
            if (s is RtspServerStream) s.startStream() else s.startStream(target)
            cfg.cameraId?.let { id -> (s.videoSource as? Camera2Source)?.takeIf { it.getCurrentCameraId() != id }?.openCameraId(id) }
            // RTSP: объявить в сети «у меня камера» — другие 2160 Player покажут её на главном экране.
            Handoff.reannounce()
            if (s is RtspServerStream) watchCode(s)
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

    /** Усиление и уровень микрофона ([GainMeterEffect.level] — для индикатора). */
    val gainMeter = GainMeterEffect()

    /** Микрофоны устройства: встроенный, гарнитура, USB, Bluetooth (без дублей по типу и имени). */
    fun microphones(context: Context): List<MicInfo> {
        if (android.os.Build.VERSION.SDK_INT < 23) return emptyList()
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return emptyList()
        return am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).mapNotNull { d ->
            val key = when (d.type) {
                android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC -> "camera.mic_builtin"
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "camera.mic_wired"
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE, android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "camera.mic_usb"
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "camera.mic_bluetooth"
                else -> if (android.os.Build.VERSION.SDK_INT >= 31 && d.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET) "camera.mic_bluetooth" else null
            } ?: return@mapNotNull null
            MicInfo(d.id, key, d.productName?.toString().orEmpty())
        }.distinctBy { it.labelKey to it.name }
    }

    private fun micDevice(context: Context, id: Int?): android.media.AudioDeviceInfo? {
        if (id == null || android.os.Build.VERSION.SDK_INT < 23) return null
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return null
        return am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
    }

    /** Сменить микрофон на лету (у работающей записи — сразу). */
    private fun applyMic(context: Context, id: Int?) {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        val source = stream?.audioSource as? MicrophoneSource ?: return
        runCatching { source.setPreferredDevice(micDevice(context, id)) }
    }

    /** Пароль RTSP: текущий код защиты; null — защита выключена. */
    private fun rtspPassword(): String? = if (HandoffAuth.required) HandoffAuth.currentCode() else null

    /** Суточный код меняется в полночь (или его сменили в настройках): обновляем пароль для новых зрителей. */
    private fun watchCode(s: RtspServerStream) {
        authJob?.cancel()
        authJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val current = (_state.value as? CameraStreamState.Streaming)?.takeIf { it.protocol == StreamProtocol.RTSP } ?: return@launch
                val pass = rtspPassword()
                if (pass != current.password) {
                    s.getStreamClient().setAuthorization(RTSP_USER.takeIf { pass != null }, pass)
                    _state.value = current.copy(password = pass)
                }
            }
        }
    }

    internal fun stopStreaming() {
        authJob?.cancel()
        authJob = null
        // Экран: трансляция кончилась — разрешение на запись больше не держим.
        if (_config.value.source == StreamSource.SCREEN) {
            stream?.let { s -> runCatching { if (s.isStreaming) s.stopStream() } }
            release()
            setProjection(null)
        }
        stream?.takeIf { it.isStreaming }?.let { runCatching { it.stopStream() } }
        if (_state.value is CameraStreamState.Streaming) {
            _state.value = CameraStreamState.Idle
            Handoff.reannounce()
            // Трансляцию остановили, пока приложение свёрнуто (кнопка в уведомлении): сервер обнаружения больше не нужен.
            val visible = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            if (!visible) Handoff.stop()
        }
        if (previewSurface == null) release()
    }

    private fun release() {
        _torch.value = false
        _zoom.value = 1f
        stream?.let { s ->
            runCatching { if (s.isStreaming) s.stopStream() }
            runCatching { if (s.isOnPreview) s.stopPreview() }
            runCatching { s.release() }
        }
        stream = null
        preparedFor = null
    }

    /**
     * Полный адрес RTMP: сервер + ключ (`rtmp://a.rtmp.youtube.com/live2/<ключ>`). null — сервер не rtmp(s)://
     * или нет ключа (у своего сервера ключ может быть уже в адресе — тогда ключ необязателен).
     */
    fun rtmpEndpoint(server: String, key: String): String? {
        val s = server.trim().trimEnd('/')
        if (!(s.startsWith("rtmp://", true) || s.startsWith("rtmps://", true))) return null
        val k = key.trim()
        return when {
            k.isNotEmpty() -> "$s/$k"
            s.count { it == '/' } >= 4 -> s // rtmp://host/app/stream — ключ уже в адресе
            else -> null
        }
    }

    /** Адрес для зрителей: IPv4 в локальной сети (как у передачи между устройствами), иначе — что сообщит сервер. */
    private fun url(context: Context): String {
        val host = Handoff.baseUrl()?.let { Uri.parse(it).host }
        if (host != null) return "rtsp://$host:${_config.value.port}/"
        return (stream as? RtspServerStream)?.getStreamClient()?.getEndPointConnection() ?: "rtsp://?:${_config.value.port}/"
    }
}
