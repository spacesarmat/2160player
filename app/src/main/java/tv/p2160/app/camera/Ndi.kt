package tv.p2160.app.camera

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.pedro.common.AudioCodec
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.library.base.StreamBase
import com.pedro.library.util.streamclient.StreamBaseClient
import com.pedro.common.socket.base.SocketType
import tv.p2160.app.BuildConfig
import java.nio.ByteBuffer

/**
 * NDI® — отправка в сеть для OBS (DistroAV), vMix и других NDI-приёмников. NDI® is a registered trademark of
 * Vizrt NDI AB (https://ndi.video/).
 *
 * Библиотека NDI SDK (`libndi.so`, закрыта) входит в APK, только если SDK был при сборке ([BuildConfig.NDI]),
 * и загружается динамически (см. `cpp/ndi_bridge.cpp`). Кадры — сырые (RGBX), NDI сжимает их сам; звук — PCM
 * 16 бит с микрофона.
 */
object Ndi {
    const val URL = "https://ndi.video/"

    /** Имя источника; NDI показывает его как «машина (имя)». */
    const val SOURCE_NAME = "2160 Player"

    private const val TAG = "Ndi"
    private var loaded: Boolean? = null

    /** NDI есть в этой сборке и библиотека загрузилась на этом устройстве. */
    @Synchronized
    fun available(context: Context): Boolean = loaded ?: (BuildConfig.NDI && runCatching {
        System.loadLibrary("p2160ndi")
        nativeLoad(writeConfig(context.applicationContext))
    }.onFailure { Log.w(TAG, "NDI unavailable", it) }.getOrDefault(false)).also { loaded = it }

    /**
     * Имя «машины» в NDI. Android отдаёт hostname «localhost», и источник выглядел бы как «LOCALHOST (…)» — такие
     * источники приёмники путают с собственным компьютером. Берём имя устройства (латиница, цифры, дефис).
     */
    fun machineName(context: Context): String {
        val raw = tv.p2160.app.handoff.Handoff.deviceName(context)
        val clean = raw.map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(40)
        return clean.ifEmpty { "Android-" + android.os.Build.MODEL.filter { it.isLetterOrDigit() } }.uppercase()
    }

    /** ndi-config.v1.json с именем машины; каталог передаётся в NDI_CONFIG_DIR до инициализации библиотеки. */
    private fun writeConfig(context: Context): String? = runCatching {
        val dir = java.io.File(context.filesDir, "ndi").apply { mkdirs() }
        val json = org.json.JSONObject().put("ndi", org.json.JSONObject().put("machinename", machineName(context)))
        java.io.File(dir, "ndi-config.v1.json").writeText(json.toString())
        dir.absolutePath
    }.onFailure { Log.w(TAG, "NDI config", it) }.getOrNull()

    /** Отправитель NDI с именем источника [name] (так его увидят OBS/vMix). */
    class Sender(name: String) : AutoCloseable {
        private var handle = nativeCreateSender(name)
        val isOpen: Boolean get() = handle != 0L

        fun sendVideo(buffer: ByteBuffer, width: Int, height: Int, stride: Int, fps: Int) {
            if (handle != 0L) nativeSendVideo(handle, buffer, width, height, stride, fps * 1000, 1000)
        }

        fun sendAudio(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (handle != 0L) nativeSendAudio(handle, pcm, pcm.size, sampleRate, channels)
        }

        /** Сколько приёмников подключено. */
        fun connections(): Int = if (handle != 0L) nativeConnections(handle) else 0

        override fun close() {
            val h = handle
            handle = 0
            if (h != 0L) nativeDestroySender(h)
        }
    }

    @JvmStatic private external fun nativeLoad(configDir: String?): Boolean
    @JvmStatic private external fun nativeCreateSender(name: String): Long
    @JvmStatic private external fun nativeSendVideo(handle: Long, buffer: ByteBuffer, width: Int, height: Int, stride: Int, fpsN: Int, fpsD: Int)
    @JvmStatic private external fun nativeSendAudio(handle: Long, pcm: ByteArray, length: Int, sampleRate: Int, channels: Int)
    @JvmStatic private external fun nativeConnections(handle: Long): Int
    @JvmStatic private external fun nativeDestroySender(handle: Long)
}

/**
 * «Поток» RootEncoder для NDI: камера/экран идут через тот же GL-конвейер (предпросмотр, зум, фокус), а кадры
 * забираются в [ImageReader] и отдаются в NDI. Кодировщики RootEncoder работают вхолостую (их вывод не нужен),
 * сеть — у NDI.
 */
internal class NdiStream(
    context: Context,
    videoSource: VideoSource,
    audioSource: AudioSource,
    private val name: String,
) : StreamBase(context, videoSource, audioSource) {
    private val appContext = context.applicationContext
    var sender: Ndi.Sender? = null
        private set
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var width = 0
    private var height = 0
    private var fps = 30
    private var frame: ByteBuffer? = null

    fun configure(width: Int, height: Int, fps: Int) {
        this.width = width
        this.height = height
        this.fps = fps
    }

    /** PCM с микрофона (из [GainMeterEffect]) — в NDI. */
    fun onPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        sender?.sendAudio(pcm, sampleRate, channels)
    }

    override fun startStreamImp(endPoint: String) {
        // mDNS-обнаружение NDI на части Wi-Fi-драйверов требует разрешить приём multicast.
        multicast = appContext.getSystemService(WifiManager::class.java)?.createMulticastLock("p2160-ndi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        sender = Ndi.Sender(name)
    }

    /**
     * Подключить приёмник кадров — после startStream (GL уже работает). Берём второй выход GL («запись»):
     * основной RootEncoder при старте занимает своим кодировщиком.
     */
    fun attachFrames() {
        if (reader != null) return
        val t = HandlerThread("ndi-video").apply { start() }
        thread = t
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                // Буфер ImageReader (память графического буфера) NDI читает как чёрный кадр — отдаём копию.
                val src = plane.buffer
                val copy = frame?.takeIf { it.capacity() >= src.remaining() }
                    ?: ByteBuffer.allocateDirect(src.remaining()).also { frame = it }
                copy.clear()
                copy.put(src)
                copy.flip()
                sender?.sendVideo(copy, image.width, image.height, plane.rowStride, fps)
            } finally {
                image.close()
            }
        }, Handler(t.looper))
        reader = r
        getGlInterface().setEncoderRecordSize(width, height)
        getGlInterface().addMediaCodecRecordSurface(r.surface)
    }

    override fun stopStreamImp() {
        runCatching { getGlInterface().removeMediaCodecRecordSurface() }
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        frame = null
        sender?.close()
        sender = null
        runCatching { multicast?.release() }
        multicast = null
    }

    override fun onAudioInfoImp(sampleRate: Int, isStereo: Boolean) = Unit
    override fun getAudioDataImp(audioBuffer: ByteBuffer, info: android.media.MediaCodec.BufferInfo) = Unit
    override fun onVideoInfoImp(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) = Unit
    override fun getVideoDataImp(videoBuffer: ByteBuffer, info: android.media.MediaCodec.BufferInfo) = Unit
    override fun setVideoCodecImp(codec: VideoCodec) = Unit
    override fun setAudioCodecImp(codec: AudioCodec) = Unit
    override fun getStreamClient(): StreamBaseClient = NdiStreamClient(this)
}

/** Клиент потока NDI: сетью управляет NDI, поэтому почти всё — пустые заглушки; «зрители» — подключения NDI. */
internal class NdiStreamClient(private val stream: NdiStream) : StreamBaseClient() {
    override fun setAuthorization(user: String?, password: String?) = Unit
    override fun reTry(delay: Long, reason: String, backupUrl: String?): Boolean = false
    override fun setReTries(reTries: Int) = Unit
    override fun hasCongestion(percentUsed: Float): Boolean = false
    override fun setLogs(enabled: Boolean) = Unit
    override fun setCheckServerAlive(enabled: Boolean) = Unit
    override fun resizeCache(newSize: Int) = Unit
    override fun clearCache() = Unit
    override fun getCacheSize(): Int = 0
    override fun getItemsInCache(): Int = 0
    override fun getQueueBytesOut(): Long = 0
    override fun getSentAudioFrames(): Long = 0
    override fun getSentVideoFrames(): Long = 0
    override fun getBytesSend(): Long = 0
    override fun getDroppedAudioFrames(): Long = 0
    override fun getDroppedVideoFrames(): Long = 0
    override fun resetSentAudioFrames() = Unit
    override fun resetSentVideoFrames() = Unit
    override fun resetDroppedAudioFrames() = Unit
    override fun resetDroppedVideoFrames() = Unit
    override fun resetBytesSend() = Unit
    override fun setOnlyAudio(onlyAudio: Boolean) = Unit
    override fun setOnlyVideo(onlyVideo: Boolean) = Unit
    override fun setBitrateExponentialFactor(factor: Float) = Unit
    override fun getBitrateExponentialFactor(): Float = 1f
    override fun setSocketType(type: SocketType) = Unit
    override fun setSocketTimeout(timeout: Long) = Unit
    override fun setDelay(millis: Long) = Unit

    /** Подключённые NDI-приёмники. */
    fun connections(): Int = stream.sender?.connections() ?: 0
}
