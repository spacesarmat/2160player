package tv.p2160.core.source

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.hierynomus.smbj.share.File
import tv.p2160.core.source.smb.SmbConnections
import tv.p2160.core.source.smb.SmbPath

/**
 * Файловый дескриптор с произвольным доступом поверх сетевого файла.
 * Нужен нативным библиотекам (FFmpeg: главы, кадры-превью), которые не умеют SMB.
 */
object SeekableFiles {

    private val thread by lazy { HandlerThread("p2160-proxy-fd").apply { start() } }

    /** null — схема не поддерживается или Android < 8.0. */
    fun open(context: Context, uri: Uri): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val path = SmbPath.fromUri(uri) ?: return null
        return openSmb(context, path)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openSmb(context: Context, path: SmbPath): ParcelFileDescriptor? = runCatching {
        val file = SmbConnections.openRead(context, path)
        val size = file.fileInformation.standardInformation.endOfFile
        val storage = context.getSystemService(StorageManager::class.java)
        storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            SmbCallback(file, size),
            Handler(thread.looper),
        )
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.O)
    private class SmbCallback(private val file: File, private val size: Long) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = size

        override fun onRead(offset: Long, length: Int, data: ByteArray): Int = try {
            var total = 0
            while (total < length) {
                val n = file.read(data, offset + total, total, length - total)
                if (n <= 0) break
                total += n
            }
            total
        } catch (e: Exception) {
            throw ErrnoException("read", OsConstants.EIO)
        }

        override fun onRelease() {
            runCatching { file.close() }
        }
    }
}
