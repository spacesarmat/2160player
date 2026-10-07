package tv.p2160.core.bluray

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import tv.p2160.core.m2ts.PidTrackHints

/** Чтение клипа `p2160disc://<id>/<n>` из открытого диска (ISO или папки BDMV). */
@OptIn(UnstableApi::class)
class DiscDataSource : BaseDataSource(/* isNetwork = */ true) {
    private var source: RandomAccessSource? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val session = DiscSessions[dataSpec.uri]
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val index = dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        transferInitializing(dataSpec)
        val clip = try {
            session.openClip(index)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        source = clip
        uri = dataSpec.uri
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else clip.size - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val src = source ?: return C.RESULT_END_OF_INPUT
        val n = try {
            src.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { source?.close() }
        source = null
        uri = null
        // transferEnded() только после успешного open(), иначе NPE скроет настоящую ошибку.
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/**
 * Фабрика источников: фильм с диска (`p2160disc://<id>`) собирается из клипов плейлиста
 * в один непрерывный ролик, остальное отдаётся [delegate].
 */
@OptIn(UnstableApi::class)
class DiscMediaSourceFactory(private val delegate: MediaSource.Factory) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        val session = uri?.takeIf { it.scheme == DiscSession.SCHEME }?.let { DiscSessions[it] }
            ?: return delegate.createMediaSource(mediaItem)
        val builder = ConcatenatingMediaSource2.Builder().setMediaItem(mediaItem)
        session.title.items.forEachIndexed { index, clip ->
            val clipItem = MediaItem.Builder()
                .setUri(session.clipUri(index))
                // Субтитры, подключённые вручную, относятся ко всему фильму — вешаем их на первый клип.
                .setSubtitleConfigurations(if (index == 0) mediaItem.localConfiguration?.subtitleConfigurations.orEmpty() else emptyList())
                .build()
            builder.add(delegate.createMediaSource(clipItem), clip.durationMs)
        }
        return builder.build()
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        delegate.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        delegate.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    companion object {
        /** Языки дорожек по PID из плейлиста диска — для [tv.p2160.core.m2ts.M2tsExtractorsFactory]. */
        fun hintsFor(uri: Uri?): PidTrackHints? {
            val session = uri?.takeIf { it.scheme == DiscSession.SCHEME }?.let { DiscSessions[it] } ?: return null
            return PidTrackHints(languageForPid = session::languageForPid)
        }
    }
}
