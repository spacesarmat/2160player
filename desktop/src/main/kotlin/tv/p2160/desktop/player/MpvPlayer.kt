package tv.p2160.desktop.player

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference
import tv.p2160.desktop.media.assignSurface
import tv.p2160.desktop.media.nextTrackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

fun loadFileCommand(url: String): List<String> = listOf("loadfile", url, "replace")

/** Копировать кадр, пока идёт воспроизведение, нет картинки, сменилась позиция или libmpv просит перерисовку. */
fun shouldCopyFrame(paused: Boolean, hasFrame: Boolean, positionChanged: Boolean, redrawRequested: Boolean): Boolean =
    !paused || !hasFrame || positionChanged || redrawRequested

/** glibc: LC_NUMERIC = 1. macOS and Windows: LC_NUMERIC = 4 (1 there is LC_COLLATE). */
fun numericLocaleCategory(osName: String): Int {
    val os = osName.lowercase()
    return when {
        os.contains("mac") -> 4
        os.contains("win") -> 4
        else -> 1
    }
}

/** Кадр в формате bgr0: на каждый пиксель B, G, R и неиспользуемый байт. */
data class VideoFrame(val width: Int, val height: Int, val bgr0: ByteArray)

data class MediaTrack(
    val id: Int,
    val type: String,
    val title: String,
    val lang: String?,
    val selected: Boolean,
)

data class PlayState(
    val title: String = "",
    val paused: Boolean = true,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val volume: Int = 80,
    val muted: Boolean = false,
    val audio: List<MediaTrack> = emptyList(),
    val subs: List<MediaTrack> = emptyList(),
    val buffering: Boolean = false,
    val error: String? = null,
    val fileLoaded: Boolean = false,
    val ended: Boolean = false,
)

/**
 * libmpv с программным кадром (MPV_RENDER_API_TYPE_SW).
 * Кадр рисует Compose, звук остаётся внутри libmpv.
 * Потолок 1920x1080: полный кадр 4K в оперативной памяти слишком дорогой для этого пути.
 */
class MpvPlayer private constructor(private val lib: MpvLib, private val handle: Pointer) : AutoCloseable {
    private val render: Pointer
    private val running = AtomicBoolean(true)
    private val wake = Object()
    private val surfaceW = AtomicInteger(0)
    private val surfaceH = AtomicInteger(0)
    private val apiType = Memory(8).apply { setString(0, "sw") }
    private val format = Memory(8).apply { setString(0, "bgr0") }
    private val renderLogs = AtomicInteger(0)
    private var buffer: Memory? = null
    private var bufferBytes = 0L

    private val _frames = MutableStateFlow<VideoFrame?>(null)
    val frames: StateFlow<VideoFrame?> = _frames.asStateFlow()

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private val frameDirty = AtomicBoolean(false)
    private val callback = RenderUpdate {
        frameDirty.set(true)
        synchronized(wake) { wake.notifyAll() }
    }

    /** libmpv попросил новый кадр, например после смены субтитров на паузе. */
    fun takeRedrawRequest(): Boolean = frameDirty.getAndSet(false)

    private val eventThread: Thread
    private val pollThread: Thread

    init {
        val params = renderParams(2)
        params[0].type = MPV_RENDER_PARAM_API_TYPE
        params[0].data = apiType
        params[1].type = MPV_RENDER_PARAM_INVALID
        params.forEach { it.write() }
        val out = PointerByReference()
        check(lib.mpv_render_context_create(out, handle, params[0]))
        render = out.value ?: error("mpv_render_context_create returned null")
        lib.mpv_render_context_set_update_callback(render, callback, null)
        eventThread = thread(name = "mpv-events", isDaemon = true) { eventLoop() }
        pollThread = thread(name = "mpv-poll", isDaemon = true) { pollLoop() }
    }

    fun renderNow() {
        if (!_state.value.fileLoaded) return
        val w = surfaceW.get()
        val h = surfaceH.get()
        if (w < 2 || h < 2) return
        try {
            renderFrame(w, h)
        } catch (t: Throwable) {
            if (renderLogs.incrementAndGet() <= 3) log("render exception: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        val (w, h) = assignSurface(width, height, MAX_W, MAX_H)
        surfaceW.set(w)
        surfaceH.set(h)
    }

    fun open(url: String, title: String, paused: Boolean, headers: Map<String, String>) {
        _state.value = _state.value.copy(title = title, error = null, fileLoaded = false, ended = false, positionSec = 0.0)
        _frames.value = null
        clearHeaders()
        headers.forEach { (key, value) ->
            command("change-list", "http-header-fields", "add", "$key: $value")
        }
        val optionsPaused = paused
        setProperty("pause", if (optionsPaused) "yes" else "no")
        if (!command(*loadFileCommand(url).toTypedArray())) {
            _state.update { it.copy(error = "Не удалось открыть") }
        }
    }

    fun setPaused(paused: Boolean) {
        setProperty("pause", if (paused) "yes" else "no")
    }

    fun stop() {
        command("stop")
    }

    fun togglePause() {
        command("cycle", "pause")
    }

    fun seekTo(seconds: Double) {
        command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")
    }

    fun seekBy(deltaSec: Double) {
        command("seek", deltaSec.toString(), "relative")
    }

    fun setVolume(volume: Int) {
        val v = volume.coerceIn(0, 100)
        setProperty("volume", v.toString())
        if (v > 0) setProperty("mute", "no")
    }

    fun toggleMute() {
        command("cycle", "mute")
    }

    fun selectAudio(id: Int?) {
        setProperty("aid", id?.toString() ?: "no")
    }

    fun selectSub(id: Int?) {
        setProperty("sid", id?.toString() ?: "no")
    }

    fun addSubtitle(path: String) {
        check(command("sub-add", path, "select"))
    }

    fun addAudio(path: String) {
        check(command("audio-add", path, "select"))
    }

    fun cycleAudio() = cycle("aid")
    fun cycleSub() = cycle("sid")

    private fun cycle(prop: String) {
        val tracks = if (prop == "aid") _state.value.audio else _state.value.subs
        val next = nextTrackId(tracks)
        if (prop == "aid") selectAudio(next) else selectSub(next)
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        eventThread.join(2_000)
        pollThread.join(2_000)
        runCatching { lib.mpv_render_context_free(render) }
        runCatching { lib.mpv_terminate_destroy(handle) }
    }

    private fun eventLoop() {
        while (running.get()) {
            val event = lib.mpv_wait_event(handle, 0.2)
            when (event.eventId) {
                MPV_EVENT_NONE -> Unit
                MPV_EVENT_SHUTDOWN -> return
                MPV_EVENT_LOG_MESSAGE -> event.data?.let { pointer ->
                    val message = MpvLogMessage(pointer)
                    val text = message.text?.trim().orEmpty()
                    if (text.isNotEmpty()) log("mpv ${message.level}: $text")
                }
                MPV_EVENT_FILE_LOADED -> _state.update { it.copy(fileLoaded = true, error = null) }
                MPV_EVENT_END_FILE -> event.data?.let { pointer ->
                    val end = MpvEndFile(pointer)
                    if (end.reason == MPV_END_FILE_REASON_ERROR) {
                        val text = lib.mpv_error_string(end.error) ?: "error ${end.error}"
                        _state.update { it.copy(error = "Воспроизведение остановилось: $text", ended = true) }
                    } else if (end.reason == 0) {
                        _state.update { it.copy(ended = true, paused = true) }
                    }
                }
            }
        }
    }

    private fun pollLoop() {
        var ticks = 0
        while (running.get()) {
            ticks++
            val position = property("time-pos")?.toDoubleOrNull() ?: 0.0
            val duration = property("duration")?.toDoubleOrNull() ?: 0.0
            val paused = property("pause") == "yes"
            val volume = property("volume")?.toDoubleOrNull()?.toInt() ?: _state.value.volume
            val muted = property("mute") == "yes"
            val buffering = property("paused-for-cache") == "yes"
            val tracks = if (ticks % 4 == 0) readTracks() else null
            _state.update { current ->
                current.copy(
                    positionSec = position,
                    durationSec = duration,
                    paused = paused,
                    volume = volume,
                    muted = muted,
                    buffering = buffering,
                    audio = tracks?.first ?: current.audio,
                    subs = tracks?.second ?: current.subs,
                )
            }
            Thread.sleep(250)
        }
    }

    private fun readTracks(): Pair<List<MediaTrack>, List<MediaTrack>> {
        val count = property("track-list/count")?.toIntOrNull() ?: return emptyList<MediaTrack>() to emptyList()
        val audio = ArrayList<MediaTrack>()
        val subs = ArrayList<MediaTrack>()
        for (index in 0 until count) {
            val type = property("track-list/$index/type") ?: continue
            val id = property("track-list/$index/id")?.toIntOrNull() ?: continue
            val title = property("track-list/$index/title")
            val lang = property("track-list/$index/lang")
            val selected = property("track-list/$index/selected") == "yes"
            val codec = property("track-list/$index/codec")
            val label = listOfNotNull(title, lang, codec).distinct().joinToString(", ").ifBlank { "$type $id" }
            val track = MediaTrack(id, type, label, lang, selected)
            when (type) {
                "audio" -> audio += track
                "sub" -> subs += track
            }
        }
        return audio to subs
    }

    private fun renderFrame(w: Int, h: Int) {
        val stride = ((w * 4 + 63) / 64) * 64
        val bytes = stride.toLong() * h + 64
        if (buffer == null || bufferBytes < bytes) {
            buffer = Memory(bytes)
            bufferBytes = bytes
        }
        val raw = buffer ?: return
        val address = Pointer.nativeValue(raw)
        val pad = ((64 - (address % 64)) % 64).toInt()
        val pixels = raw.share(pad.toLong())
        val size = Memory(8).apply {
            setInt(0, w)
            setInt(4, h)
        }
        val strideMem = Memory(8).apply { setLong(0, stride.toLong()) }
        val params = renderParams(5)
        params[0].apply { type = MPV_RENDER_PARAM_SW_SIZE; data = size }
        params[1].apply { type = MPV_RENDER_PARAM_SW_FORMAT; data = format }
        params[2].apply { type = MPV_RENDER_PARAM_SW_STRIDE; data = strideMem }
        params[3].apply { type = MPV_RENDER_PARAM_SW_POINTER; data = pixels }
        params[4].apply { type = MPV_RENDER_PARAM_INVALID; data = null }
        params.forEach { it.write() }
        val rc = lib.mpv_render_context_render(render, params[0])
        if (rc < 0) {
            log("render failed: ${lib.mpv_error_string(rc)}")
            return
        }
        if (renderLogs.incrementAndGet() <= 3) log("rendered ${w}x$h rc=$rc")
        val packed = ByteArray(w * h * 4)
        val chunk = ByteArray(w * 4)
        for (y in 0 until h) {
            pixels.read(y.toLong() * stride, chunk, 0, chunk.size)
            System.arraycopy(chunk, 0, packed, y * w * 4, chunk.size)
        }
        var i = 3
        while (i < packed.size) {
            packed[i] = 0xFF.toByte()
            i += 4
        }
        _frames.value = VideoFrame(w, h, packed)
    }

    private fun renderParams(count: Int): Array<MpvRenderParam> {
        @Suppress("UNCHECKED_CAST")
        return MpvRenderParam().toArray(count) as Array<MpvRenderParam>
    }

    private fun clearHeaders() {
        command("change-list", "http-header-fields", "clr", "")
    }

    private fun property(name: String): String? {
        val pointer = lib.mpv_get_property_string(handle, name) ?: return null
        return try {
            pointer.getString(0)
        } finally {
            lib.mpv_free(pointer)
        }
    }

    private fun setProperty(name: String, value: String) {
        val rc = lib.mpv_set_property_string(handle, name, value)
        if (rc < 0) log("set $name=$value: ${lib.mpv_error_string(rc)}")
    }

    private fun command(vararg args: String): Boolean {
        val rc = lib.mpv_command(handle, StringArray(args))
        if (rc < 0) {
            log("command ${args.joinToString(" ")}: ${lib.mpv_error_string(rc)}")
            return false
        }
        return true
    }

    private fun check(rc: Int) {
        if (rc < 0) error(lib.mpv_error_string(rc) ?: "mpv error $rc")
    }

    companion object {
        const val MAX_W = 1920
        const val MAX_H = 1080

        fun start(): MpvPlayer {
            forceCNumericLocale()
            val lib = NativeLibs.loadMpv()
            val handle = lib.mpv_create() ?: error("mpv_create вернул пустой указатель")
            fun option(name: String, value: String) {
                val rc = lib.mpv_set_option_string(handle, name, value)
                if (rc < 0) log("option $name=$value: ${lib.mpv_error_string(rc)}")
            }
            option("vo", "libmpv")
            option("hwdec", "no")
            option("idle", "yes")
            option("keep-open", "yes")
            option("force-window", "no")
            option("osc", "no")
            option("input-default-bindings", "no")
            option("input-vo-keyboard", "no")
            option("ytdl", "no")
            option("user-agent", "2160Player/0.2.26")
            option("cache", "yes")
            option("demuxer-readahead-secs", "8")
            option("network-timeout", "120")
            option("sub-auto", "no")
            option("audio-file-auto", "no")
            check(lib.mpv_initialize(handle))
            check(lib.mpv_request_log_messages(handle, "warn"))
            return MpvPlayer(lib, handle)
        }

        /** Ждёт первый кадр и ничего не показывает. Для проверки, что файл реально декодируется. */
        fun dumpFirstFrame(media: String, out: java.io.File, timeoutMs: Long = 20_000) {
            val player = start()
            try {
                player.setSurfaceSize(640, 360)
                player.open(media, media, paused = true, headers = emptyMap())
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                while (System.nanoTime() < deadline) {
                    if (player.state.value.fileLoaded) {
                        player.renderNow()
                        val frame = player.frames.value
                        if (frame != null) {
                            writePng(frame, out)
                            log("frame ${frame.width}x${frame.height} -> ${out.absolutePath}")
                            return
                        }
                    }
                    val state = player.state.value
                    if (state.error != null) error(state.error)
                    log("wait loaded=${state.fileLoaded} pos=${state.positionSec}")
                    Thread.sleep(300)
                }
                error("кадр не появился за ${timeoutMs / 1000} с")
            } finally {
                player.close()
            }
        }

        fun writePng(frame: VideoFrame, out: java.io.File) {
            val image = java.awt.image.BufferedImage(frame.width, frame.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            var i = 0
            for (y in 0 until frame.height) {
                for (x in 0 until frame.width) {
                    val b = frame.bgr0[i].toInt() and 0xFF
                    val g = frame.bgr0[i + 1].toInt() and 0xFF
                    val r = frame.bgr0[i + 2].toInt() and 0xFF
                    image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
                    i += 4
                }
            }
            out.parentFile?.mkdirs()
            javax.imageio.ImageIO.write(image, "png", out)
        }

        private fun forceCNumericLocale() {
            // libmpv отказывается создаваться, если LC_NUMERIC не "C".
            val os = System.getProperty("os.name").lowercase()
            val libc = if (os.contains("win")) {
                runCatching { Native.load("ucrtbase", CLocale::class.java).setlocale(4, "C") }
                Native.load("msvcrt", CLocale::class.java)
            } else {
                Native.load("c", CLocale::class.java)
            }
            val category = numericLocaleCategory(os)
            libc.setlocale(category, "C")
        }

        private interface CLocale : com.sun.jna.Library {
            fun setlocale(category: Int, locale: String): Pointer?
        }

        private fun check(rc: Int) {
            if (rc < 0) error("mpv error $rc")
        }
    }
}
