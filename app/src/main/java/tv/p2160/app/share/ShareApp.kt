package tv.p2160.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import tv.p2160.app.BuildConfig
import tv.p2160.app.handoff.Handoff
import tv.p2160.app.update.Updater
import java.io.File
import java.util.zip.ZipFile

/**
 * «Поделиться приложением» без магазина и, при желании, без интернета:
 * - файлом — свой установленный APK через системное «Поделиться» (Quick Share, Bluetooth, Telegram…);
 * - по Wi-Fi — тот же APK отдаёт сервер передачи между устройствами (`GET /app/…`), адрес и QR-код
 *   показываются на экране; работает и через точку доступа телефона;
 * - ссылкой — последний релиз на GitHub (нужен интернет).
 *
 * Если установлен APK под одну архитектуру (например, только arm64-v8a), рядом хранится «полный» пакет —
 * общий ARM-APK той же версии из релиза ([prepareFull]): им и делимся. Хранится только файл текущей версии,
 * старые удаляются при запуске; после перехода на общий ARM-APK папка очищается целиком.
 */
object ShareApp {
    const val RELEASES_URL = "https://github.com/${Updater.REPO}/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** Установленный APK этого приложения. */
    fun apk(context: Context): File = File(context.applicationInfo.sourceDir)

    /** Имя файла для получателя. */
    fun fileName(): String = "2160player-${BuildConfig.VERSION_NAME}.apk"

    /** Архитектуры, под которые собран установленный APK (папки lib/<abi>/). */
    fun abis(context: Context): Set<String> = runCatching {
        ZipFile(apk(context)).use { zip ->
            zip.entries().asSequence().mapNotNull { e -> e.name.takeIf { it.startsWith("lib/") }?.split('/')?.getOrNull(1) }.toSet()
        }
    }.getOrDefault(emptySet())

    /** Подойдёт ли файл и телефонам (arm64), и старым 32-битным ТВ (armeabi-v7a). */
    fun fitsAllArmDevices(abis: Set<String>): Boolean = "arm64-v8a" in abis && "armeabi-v7a" in abis

    /** Состояние полного пакета для «Поделиться». */
    sealed interface FullState {
        /** Не нужен (установлен общий ARM-APK) или ещё не скачан. */
        data object None : FullState
        data class Downloading(val progress: Float) : FullState
        data class Ready(val file: File) : FullState
        data class Failed(val message: String) : FullState
    }

    private val _full = MutableStateFlow<FullState>(FullState.None)
    val full: StateFlow<FullState> = _full.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    @Volatile private var installedAbis: Set<String>? = null

    private fun fullDir(context: Context) = File(context.filesDir, "share-apk")
    private fun fullFile(context: Context) = File(fullDir(context), "2160player-${BuildConfig.VERSION_NAME}-arm.apk")

    /** Нужен ли полный пакет: установленный APK подходит не всем ARM-устройствам. */
    fun needsFull(context: Context): Boolean {
        val abis = installedAbis ?: abis(context).also { installedAbis = it }
        return abis.isNotEmpty() && !fitsAllArmDevices(abis)
    }

    /** Что отдавать получателю: полный пакет, если он нужен и скачан, иначе установленный APK. */
    fun sharedApk(context: Context): File =
        fullFile(context).takeIf { needsFull(context) && it.isFile } ?: apk(context)

    /**
     * Убрать старые пакеты и, если нужно, скачать общий ARM-APK текущей версии в `filesDir/share-apk/`.
     * [auto] — фоновый запуск при старте: только по безлимитной сети (Wi-Fi), ошибки не показываются.
     */
    fun prepareFull(context: Context, auto: Boolean) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try {
                prepare(app, auto)
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun prepare(context: Context, auto: Boolean) {
        val dir = fullDir(context)
        val target = fullFile(context)
        val needed = needsFull(context)
        // Не копим мусор: всё, кроме пакета текущей версии (и его целиком, если он больше не нужен).
        dir.listFiles()?.filter { !needed || it != target }?.forEach { it.delete() }
        if (!needed) { _full.value = FullState.None; return }
        if (target.isFile) { _full.value = FullState.Ready(target); return }
        if (auto && !unmetered(context)) return
        _full.value = FullState.Downloading(0f)
        val part = File(dir.apply { mkdirs() }, target.name + ".part")
        try {
            val (url, size) = Updater.sharedAsset(BuildConfig.VERSION_NAME)
            val conn = Updater.open(url)
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: size
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - lastReport > 256 * 1024) {
                            lastReport = done
                            _full.value = FullState.Downloading(done.toFloat() / total)
                        }
                    }
                }
            }
            // Проверяем, что скачали именно этот плеер этой версии.
            val info = context.packageManager.getPackageArchiveInfo(part.path, 0)
            check(info?.packageName == Updater.RELEASE_PACKAGE && info.versionName == BuildConfig.VERSION_NAME) {
                "unexpected package ${info?.packageName} ${info?.versionName}"
            }
            check(part.renameTo(target)) { "rename failed" }
            _full.value = FullState.Ready(target)
        } catch (e: Exception) {
            part.delete()
            Log.w("ShareApp", "full package: ${e.message}")
            _full.value = if (auto) FullState.None else FullState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun unmetered(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    /** Отправить APK файлом через системное меню «Поделиться». */
    fun shareFile(context: Context, chooserTitle: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val full = sharedApk(context).takeIf { it != apk(context) }
        // Полный пакет лежит в filesDir и отдаётся как есть; установленный APK копируем в кэш под понятным именем.
        val copy = full ?: File(dir, fileName()).also { apk(context).copyTo(it, overwrite = true) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", copy)
        val send = Intent(Intent.ACTION_SEND)
            .setType(APK_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(copy.name, uri)
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Отправить ссылку на релиз. */
    fun shareLink(context: Context, text: String, chooserTitle: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$text $RELEASES_URL")
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Адрес для скачивания по Wi-Fi или null, если сети нет. */
    fun wifiUrl(): String? = Handoff.baseUrl()?.let { "$it/app/${fileName()}" }

    /** QR-код для адреса. */
    fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
