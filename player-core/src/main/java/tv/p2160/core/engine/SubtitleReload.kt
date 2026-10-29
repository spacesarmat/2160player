package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource

/**
 * Media3 считает, что [androidx.media3.exoplayer.source.ProgressiveMediaSource] можно обновить
 * на месте, если у видео тот же URI. Список [MediaItem.SubtitleConfiguration] в это сравнение
 * не входит: `replaceMediaItem` после «Загрузить из файла…» меняет только карточку элемента,
 * источник субтитров не создаётся, и дорожка не появляется.
 * Если набор субтитров изменился, обновление запрещено - плеер собирает источник заново.
 */
@OptIn(UnstableApi::class)
internal object SubtitleReload {

    class Factory(private val delegate: MediaSource.Factory) : MediaSource.Factory by delegate {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            Source(delegate.createMediaSource(mediaItem))
    }

    class Source(delegate: MediaSource) : WrappingMediaSource(delegate) {
        override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
            if (configurations(getMediaItem()) != configurations(mediaItem)) return false
            return super.canUpdateMediaItem(mediaItem)
        }
    }

    fun configurations(item: MediaItem): List<MediaItem.SubtitleConfiguration> =
        item.localConfiguration?.subtitleConfigurations ?: emptyList()
}
