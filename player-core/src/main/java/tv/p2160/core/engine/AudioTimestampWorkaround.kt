package tv.p2160.core.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

@OptIn(UnstableApi::class)
internal fun ignoreAudioTimestamp(manufacturer: String, sdkInt: Int, encoding: Int, tunneling: Boolean, offload: Boolean): Boolean =
    manufacturer.equals("Realtek", ignoreCase = true) && sdkInt == 28 && !Util.isEncodingLinearPcm(encoding) && !tunneling && !offload

internal fun playbackHeadFrames(raw: Long, previousRaw: Long, wrapCount: Int): Long {
    val wraps = if (previousRaw > raw) wrapCount + 1 else wrapCount
    return raw + (wraps.toLong() shl 32)
}

@OptIn(UnstableApi::class)
internal class PlaybackHeadClock {
    private var lastRaw = 0L
    private var wraps = 0
    private var stoppedAtNs: Long? = null
    private var stopPositionUs = 0L

    fun positionUs(raw: Long, sampleRate: Int, elapsedRealtimeNanos: Long): Long {
        val stoppedAt = stoppedAtNs
        if (stoppedAt != null) return stopPositionUs + (elapsedRealtimeNanos - stoppedAt) / 1_000L
        val frames = playbackHeadFrames(raw, lastRaw, wraps)
        if (lastRaw > raw) wraps++
        lastRaw = raw
        return Util.sampleCountToDurationUs(frames, sampleRate)
    }

    fun stop(raw: Long, sampleRate: Int, elapsedRealtimeNanos: Long) {
        if (stoppedAtNs != null) return
        stopPositionUs = positionUs(raw, sampleRate, elapsedRealtimeNanos)
        stoppedAtNs = elapsedRealtimeNanos
    }

    fun flush() {
        lastRaw = 0L
        wraps = 0
        stoppedAtNs = null
        stopPositionUs = 0L
    }
}

@OptIn(UnstableApi::class)
private class PlaybackHeadAudioOutput(delegate: AudioTrackAudioOutput) : ForwardingAudioOutput(delegate) {
    private val track = delegate.audioTrack
    private val clock = PlaybackHeadClock()

    override fun getPositionUs(): Long {
        val raw = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return clock.positionUs(raw, track.sampleRate, SystemClock.elapsedRealtimeNanos())
    }

    override fun stop() {
        clock.stop(track.playbackHeadPosition.toLong() and 0xFFFFFFFFL, track.sampleRate, SystemClock.elapsedRealtimeNanos())
        super.stop()
    }

    override fun flush() {
        clock.flush()
        super.flush()
    }
}

@OptIn(UnstableApi::class)
internal class PassthroughAudioOutputProvider(context: Context) : ForwardingAudioOutputProvider(
    AudioTrackAudioOutputProvider.Builder(context).build(),
) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        if (!ignoreAudioTimestamp(Build.MANUFACTURER, Build.VERSION.SDK_INT, config.encoding, config.isTunneling, config.isOffload)) {
            return output
        }
        Log.i("Player2160", "Realtek compressed audio uses playback head clock")
        return PlaybackHeadAudioOutput(output as AudioTrackAudioOutput)
    }
}
