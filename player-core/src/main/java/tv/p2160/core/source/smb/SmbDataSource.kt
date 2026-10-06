package tv.p2160.core.source.smb

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.hierynomus.smbj.share.File
import java.io.IOException

/**
 * Источник данных Media3 для `smb://host/share/path`.
 * Читает крупными блоками: на 4K-ремуксе (≈ 80–120 Мбит/с) мелкие запросы по сети
 * упираются в задержку, а не в канал.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {

    private var file: File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private val buffer = ByteArray(BUFFER_SIZE)
    private var bufferStart = 0L
    private var bufferLength = 0

    override fun open(dataSpec: DataSpec): Long {
        val path = SmbPath.fromUri(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val opened = try {
            SmbConnections.openRead(context, path)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = opened
        val size = opened.fileInformation.standardInformation.endOfFile
        if (dataSpec.position > size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        bufferLength = 0
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("not opened")

        if (position < bufferStart || position >= bufferStart + bufferLength) {
            val toRead = minOf(BUFFER_SIZE.toLong(), bytesRemaining).toInt()
            val n = try {
                f.read(buffer, position, 0, toRead)
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bufferStart = position
            bufferLength = n
        }
        val inBuffer = (position - bufferStart).toInt()
        val count = minOf(length.toLong(), (bufferLength - inBuffer).toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, inBuffer, target, offset, count)
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            file?.close()
        } catch (_: Exception) {
        } finally {
            file = null
            uri = null
            bufferLength = 0
            transferEnded()
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(context.applicationContext)
    }

    private companion object {
        const val BUFFER_SIZE = 2 * 1024 * 1024
    }
}
