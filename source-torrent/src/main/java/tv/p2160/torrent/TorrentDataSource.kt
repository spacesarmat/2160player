package tv.p2160.torrent

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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Media3-источник для `torrent://<id>/<fileIndex>/<имя>`: читает файл по мере скачивания.
 * `read` блокирует поток загрузчика плеера, пока нужный кусок не скачан; позиция чтения
 * сдвигает приоритеты кусков (перемотка тоже).
 */
@OptIn(UnstableApi::class)
class TorrentDataSource(private val engine: TorrentEngine) : BaseDataSource(/* isNetwork = */ true) {
    private var stream: TorrentSession.FileStream? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (id, index) = TorrentEngine.parseUri(dataSpec.uri)
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val s = try {
            engine.openStream(id, index)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        } catch (e: RuntimeException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (dataSpec.position > s.size) {
            s.close()
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        stream = s
        position = dataSpec.position
        remaining = s.size - position
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val s = stream ?: throw IOException("not opened")
        val n = try {
            s.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: SocketTimeoutException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            stream?.close()
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(TorrentEngine.get(context))
    }
}
