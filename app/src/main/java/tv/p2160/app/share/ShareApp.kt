package tv.p2160.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
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

    /** Отправить APK файлом через системное меню «Поделиться». */
    fun shareFile(context: Context, chooserTitle: String) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val copy = File(dir, fileName())
        apk(context).copyTo(copy, overwrite = true)
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
