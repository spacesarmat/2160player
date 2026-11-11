package tv.p2160.desktop.player

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.Structure

internal interface MpvLib : Library {
    fun mpv_error_string(error: Int): String?
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_command(ctx: Pointer, args: Pointer): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, value: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer?)
    fun mpv_request_log_messages(ctx: Pointer, level: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEvent
    fun mpv_render_context_create(out: com.sun.jna.ptr.PointerByReference, ctx: Pointer, params: MpvRenderParam): Int
    fun mpv_render_context_set_update_callback(ctx: Pointer, cb: RenderUpdate, data: Pointer?)
    fun mpv_render_context_update(ctx: Pointer): Long
    fun mpv_render_context_render(ctx: Pointer, params: MpvRenderParam): Int
    fun mpv_render_context_free(ctx: Pointer)
}

internal fun interface RenderUpdate : Callback {
    fun invoke(ctx: Pointer?)
}

@Structure.FieldOrder("type", "data")
internal class MpvRenderParam : Structure() {
    @JvmField var type: Int = 0
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("eventId", "error", "replyUserdata", "data")
internal class MpvEvent : Structure() {
    @JvmField var eventId: Int = 0
    @JvmField var error: Int = 0
    @JvmField var replyUserdata: Long = 0
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("prefix", "level", "text", "logLevel")
internal class MpvLogMessage(p: Pointer) : Structure(p) {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null
    @JvmField var logLevel: Int = 0

    init {
        read()
    }
}

@Structure.FieldOrder("reason", "error")
internal class MpvEndFile(p: Pointer) : Structure(p) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0

    init {
        read()
    }
}

internal const val MPV_EVENT_NONE = 0
internal const val MPV_EVENT_SHUTDOWN = 1
internal const val MPV_EVENT_LOG_MESSAGE = 2
internal const val MPV_EVENT_END_FILE = 7
internal const val MPV_EVENT_FILE_LOADED = 8
internal const val MPV_END_FILE_REASON_ERROR = 4
internal const val MPV_RENDER_PARAM_INVALID = 0
internal const val MPV_RENDER_PARAM_API_TYPE = 1
internal const val MPV_RENDER_PARAM_SW_SIZE = 17
internal const val MPV_RENDER_PARAM_SW_FORMAT = 18
internal const val MPV_RENDER_PARAM_SW_STRIDE = 19
internal const val MPV_RENDER_PARAM_SW_POINTER = 20
internal const val MPV_RENDER_UPDATE_FRAME = 1L
