package tv.p2160.core.engine

import android.app.Activity
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Живая статистика воспроизведения для слоя «Статистика» поверх видео. */
data class PlaybackStats(
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** Звук уходит на ресивер без декодирования. */
    val audioPassthrough: Boolean = false,
    /** Звук декодирует аудиочип (offload), процессор может спать. */
    val audioOffload: Boolean = false,
    /** Туннельный режим запрошен для видео. */
    val tunneling: Boolean = false,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoCodec: String? = null,
    val frameRate: Float = -1f,
    val renderedFrames: Int = 0,
    val droppedFrames: Int = 0,
    /** Сколько секунд и байт в буфере, и предел буфера в байтах. */
    val bufferMs: Long = 0,
    val bufferBytes: Long = 0,
    val bufferTargetBytes: Long = 0,
    /** Оценка скорости сети, бит/с (0 — нет данных). */
    val bandwidthBps: Long = 0,
    /** Загрузка процессора процессом плеера, % одного ядра. */
    val cpuPercent: Float = 0f,
    /** Память процесса (PSS), МБ, и занятая куча Java / нативная куча, МБ. */
    val memoryMb: Int = 0,
    val heapMb: Int = 0,
    val nativeHeapMb: Int = 0,
)

/**
 * Частота кадров по меткам времени кадров — для контейнеров, где её нет в заголовке (часто MKV).
 * Медиана интервалов первых кадров, приведённая к стандартной частоте (23,976, 25, 29,97…).
 */
@OptIn(UnstableApi::class)
internal class FrameRateProbe(private val onDetected: (Float) -> Unit) : androidx.media3.exoplayer.video.VideoFrameMetadataListener {
    private val deltas = LongArray(SAMPLES)
    private var count = 0
    private var last = -1L
    @Volatile private var done = false

    /** Новый файл или перемотка — начать заново. */
    fun reset() {
        count = 0
        last = -1L
        done = false
    }

    override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: androidx.media3.common.Format, mediaFormat: android.media.MediaFormat?) {
        if (done) return
        val prev = last
        last = presentationTimeUs
        if (prev < 0) return
        val d = presentationTimeUs - prev
        // Разрывы (перемотка, пропуск) и мусор — мимо: 10–100 fps.
        if (d !in 10_000..100_000) return
        deltas[count++] = d
        if (count < SAMPLES) return
        done = true
        // Медиана отсекает пропуски кадров (двойной интервал), а среднее соседних с ней — округление меток
        // в MKV до 1 мс (23,976 fps — то 41, то 42 мс).
        deltas.sort()
        val median = deltas[SAMPLES / 2]
        val near = deltas.filter { abs(it - median) <= 1_500 }
        val fps = 1_000_000f / near.average().toFloat()
        onDetected(STANDARD.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) / fps < 0.01f } ?: fps)
    }

    private companion object {
        const val SAMPLES = 48
        val STANDARD = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f).toList()
    }
}

/** Настройки аудио offload: включаем, если разрешено и нет обработки звука (ночной режим её требует). */
@OptIn(UnstableApi::class)
internal fun offloadPreferences(enabled: Boolean): AudioOffloadPreferences =
    if (!enabled) AudioOffloadPreferences.DEFAULT
    else AudioOffloadPreferences.Builder()
        .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
        .setIsGaplessSupportRequired(false)
        .setIsSpeedChangeSupportRequired(true)
        .build()

/**
 * Сбор статистики: декодеры, кадры, буфер, сеть, процессор и память. Работает, только пока включён
 * ([start]/[stop]) — опрос памяти не бесплатен.
 */
@OptIn(UnstableApi::class)
internal class StatsCollector(
    private val player: ExoPlayer,
    private val allocator: DefaultAllocator,
    private val bufferTargetBytes: Long,
    private val decoders: () -> ActiveDecoders,
    private val tunneling: () -> Boolean,
    private val frameRate: () -> Float,
) {
    private val _stats = MutableStateFlow<PlaybackStats?>(null)
    val stats: StateFlow<PlaybackStats?> = _stats.asStateFlow()

    @Volatile private var bandwidth = 0L
    @Volatile var offload = false
        private set
    private var job: Job? = null

    val analytics = object : AnalyticsListener {
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            bandwidth = bitrateEstimate
        }
    }

    val offloadListener = object : ExoPlayer.AudioOffloadListener {
        override fun onOffloadedPlayback(offloadedPlayback: Boolean) {
            offload = offloadedPlayback
        }
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            var lastCpu = cpuTicks()
            var lastAt = SystemClock.elapsedRealtime()
            var memory = Triple(0, 0, 0)
            var n = 0
            while (isActive) {
                if (n++ % 2 == 0) memory = withContext(Dispatchers.Default) { memorySnapshot() }
                val cpu = cpuTicks()
                val now = SystemClock.elapsedRealtime()
                val seconds = (now - lastAt) / 1000f
                val cpuPercent = if (cpu >= 0 && lastCpu >= 0 && seconds > 0) (cpu - lastCpu) / CLOCK_TICKS / seconds * 100f else 0f
                lastCpu = cpu
                lastAt = now
                _stats.value = snapshot(cpuPercent, memory)
                delay(1_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _stats.value = null
    }

    private fun snapshot(cpuPercent: Float, memory: Triple<Int, Int, Int>): PlaybackStats {
        val d = decoders()
        val v = player.videoFormat
        val counters = player.videoDecoderCounters
        return PlaybackStats(
            videoDecoder = d.video,
            audioDecoder = d.audio,
            audioPassthrough = d.audioPassthrough,
            audioOffload = offload,
            tunneling = tunneling() && v != null,
            videoWidth = v?.width ?: 0,
            videoHeight = v?.height ?: 0,
            videoCodec = v?.sampleMimeType?.substringAfter('/'),
            frameRate = frameRate(),
            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
            droppedFrames = counters?.droppedBufferCount ?: 0,
            bufferMs = player.totalBufferedDuration,
            bufferBytes = allocator.totalBytesAllocated.toLong(),
            bufferTargetBytes = bufferTargetBytes,
            bandwidthBps = bandwidth,
            cpuPercent = cpuPercent,
            memoryMb = memory.first,
            heapMb = memory.second,
            nativeHeapMb = memory.third,
        )
    }

    /** utime + stime процесса в тиках (поля 14 и 15 из /proc/self/stat); -1 — недоступно. */
    private fun cpuTicks(): Long = runCatching {
        val stat = File("/proc/self/stat").readText()
        val fields = stat.substringAfterLast(')').trim().split(' ')
        // После «(имя)»: поле 3 — индекс 0, значит utime (14) — индекс 11, stime (15) — 12.
        fields[11].toLong() + fields[12].toLong()
    }.getOrDefault(-1)

    private fun memorySnapshot(): Triple<Int, Int, Int> {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val rt = Runtime.getRuntime()
        return Triple(
            info.totalPss / 1024,
            ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt(),
            (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt(),
        )
    }

    private companion object {
        val CLOCK_TICKS: Float = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK).toFloat() }.getOrDefault(100f).coerceAtLeast(1f)
    }
}

/**
 * Частота экрана под видео (как в Kodi/Nova): ТВ переключается в режим с тем же разрешением и частотой,
 * кратной частоте кадров (23,976 → 23,976/47,952 Гц, 25 → 50 Гц…), — пропадает дёрганье на панорамах.
 * На время переключения (HDMI пересинхронизируется 1–3 с) воспроизведение ставится на паузу.
 */
@OptIn(UnstableApi::class)
object FrameRateMatcher {
    /** Лучший режим дисплея для [fps] или null, если подходящего нет. */
    fun bestMode(modes: Array<Display.Mode>, current: Display.Mode, fps: Float): Display.Mode? {
        if (fps < 10f) return null
        return modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { m ->
                val k = (m.refreshRate / fps).roundToInt()
                if (k < 1) return@mapNotNull null
                val err = abs(m.refreshRate - k * fps) / (k * fps)
                // 24 и 23,976 различаются на 0,1 %: точное совпадение важнее кратности.
                if (err > 0.002f) null else m to (err * 1000f + (k - 1) * 0.1f)
            }
            .minByOrNull { it.second }?.first
    }

    /**
     * Переключить режим окна [activity] под [fps]. Пока дисплей переключается — пауза, потом
     * воспроизведение продолжается, если шло.
     */
    suspend fun apply(activity: Activity, player: Player, fps: Float, onSwitching: () -> Unit = {}) {
        val display = display(activity) ?: return
        val target = bestMode(display.supportedModes, display.mode, fps) ?: return
        val window = activity.window
        if (window.attributes.preferredDisplayModeId == target.modeId) return
        val switching = display.mode.modeId != target.modeId
        val wasPlaying = player.playWhenReady
        if (switching) {
            onSwitching()
            player.playWhenReady = false
        }
        window.attributes = window.attributes.also { it.preferredDisplayModeId = target.modeId }
        if (!switching) return
        withTimeoutOrNull(4_000) {
            while (display.mode.modeId != target.modeId) delay(100)
        }
        // ТВ после смены режима ещё показывает чёрный экран, а HDMI заново поднимает звук (ресивер).
        delay(1_500)
        if (wasPlaying) player.playWhenReady = true
    }

    /** Вернуть режим дисплея по умолчанию. */
    fun reset(activity: Activity) {
        val window = activity.window ?: return
        if (window.attributes.preferredDisplayModeId != 0) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = 0 }
        }
    }

    /** Текущая частота экрана, Гц. */
    fun refreshRate(activity: Activity): Float = display(activity)?.mode?.refreshRate ?: 0f

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
}
