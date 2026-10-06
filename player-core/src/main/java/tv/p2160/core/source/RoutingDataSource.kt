package tv.p2160.core.source

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import tv.p2160.core.source.smb.SmbDataSource

/** Выбирает источник по схеме URI: `smb://` — наш SMB, остальное — стандартный DefaultDataSource. */
@OptIn(UnstableApi::class)
class RoutingDataSource(
    private val context: Context,
    private val default: DataSource,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var smb: DataSource? = null
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        default.addTransferListener(transferListener)
        smb?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = when (dataSpec.uri.scheme?.lowercase()) {
            "smb" -> smb ?: SmbDataSource(context).also { s -> listeners.forEach(s::addTransferListener); smb = s }
            else -> default
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: throw IllegalStateException("not opened")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val context: Context, private val default: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context.applicationContext, default.createDataSource())
    }
}
