package tv.p2160.core.diagnostics

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Тестовый режим: журнал действий программы и пользователя.
 * Включается в настройках; пока выключен, [event] - дешёвый no-op.
 * Каждая строка дополнительно уходит в logcat (tag [LOG_TAG]), а падения -
 * в журнал через [installCrashHandler] до передачи системному обработчику.
 *
 * Файл: files/diagnostics/actions-yyyyMMdd-HHmmss.log, ротация по размеру
 * [Sink.MAX_BYTES] и хранение последних [Sink.KEEP_FILES] файлов.
 */
object ActionLog {
    const val LOG_TAG = "p2160.actions"
    private const val PREFS = "p2160_diagnostics"
    private const val KEY_ENABLED = "enabled"

    @Volatile private var sink: Sink? = null
    @Volatile private var enabled: Boolean = false
    @Volatile private var crashHandlerInstalled = false

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
        init(context)
        event("diagnostics", if (value) "enabled" else "disabled")
    }

    /** Вызывать в Application.onCreate до любых событий. */
    @Synchronized
    fun init(context: Context) {
        val on = enabled(context)
        enabled = on
        if (on && sink == null) {
            val dir = File(context.filesDir, "diagnostics")
            sink = Sink(dir).also { it.openSession() }
            installCrashHandler()
            event("diagnostics", "session start ${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
        } else if (!on) {
            sink?.close()
            sink = null
        }
    }

    /** Событие в журнал. При выключенном режиме не пишет ни в файл, ни в logcat. */
    fun event(source: String, message: String) {
        if (!enabled) return
        val line = "${Sink.time()} | $source | $message"
        Log.i(LOG_TAG, line)
        val s = sink ?: return
        synchronized(s) { s.write(line) }
    }

    /** Поделиться текущим журналом через системное меню. null - журнала ещё нет. */
    fun shareIntent(context: Context): Intent? {
        val file = sink?.currentFile() ?: latestFile(context) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun latestFile(context: Context): File? =
        File(context.filesDir, "diagnostics").listFiles()
            ?.filter { it.isFile && it.name.startsWith("actions-") && it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stack = android.util.Log.getStackTraceString(throwable).replace("\n", " ⏎ ")
                event("crash", "thread=${thread.name} $stack")
                sink?.flush()
            }
            previous?.uncaughtException(thread, throwable) ?: run { kotlin.system.exitProcess(1) }
        }
    }

    /** Файловая часть без Android: формат строк, сессии, ротация. Покрыта JVM-тестами. */
    internal class Sink(val dir: File, val maxBytes: Long = MAX_BYTES, val keepFiles: Int = KEEP_FILES) {
        private var writer: BufferedWriter? = null
        private var current: File? = null
        private var sessionSeq = 0

        fun openSession() {
            dir.mkdirs()
            cleanup()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            // Ротация внутри одной секунды не должна попасть в тот же файл.
            current = File(dir, "actions-$stamp-%03d.log".format(sessionSeq++))
            writer = BufferedWriter(FileWriter(current, true))
        }

        fun currentFile(): File? = current?.takeIf { it.length() > 0 }

        @Synchronized
        fun write(line: String) {
            val w = writer ?: return
            w.write(line)
            w.newLine()
            // Тестовый режим ищут после падений: каждая строка - сразу на диске.
            w.flush()
            val f = current ?: return
            if (f.length() > maxBytes) {
                w.close()
                openSession()
            }
        }

        @Synchronized
        fun flush() {
            runCatching { writer?.flush() }
        }

        @Synchronized
        fun close() {
            runCatching { writer?.close() }
            writer = null
        }

        private fun cleanup() {
            val old = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("actions-") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            old.drop(keepFiles).forEach { it.delete() }
        }

        companion object {
            const val MAX_BYTES = 4L * 1024 * 1024
            const val KEEP_FILES = 8
            fun time(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        }
    }
}
