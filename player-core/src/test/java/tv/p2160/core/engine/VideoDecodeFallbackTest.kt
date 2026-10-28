package tv.p2160.core.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.p2160.core.settings.DecoderPreference

@OptIn(UnstableApi::class)
class VideoDecodeFallbackTest {
    @Test
    fun autoStepsFromHardwareToSoftwareThenFfmpegOnlyWhenTheDecoderExists() {
        val gate = VideoDecoderGate()
        assertEquals(
            VideoDecoderGate.SOFTWARE,
            gate.advance(MimeTypes.VIDEO_MP4V, "OMX.allwinner.video.decoder.mpeg4", allowFallback = true, ffmpegAvailable = true),
        )
        assertEquals(
            VideoDecoderGate.FFMPEG,
            gate.advance(MimeTypes.VIDEO_MP4V, "c2.android.mpeg4.decoder", allowFallback = true, ffmpegAvailable = true),
        )
        assertNull(gate.advance(MimeTypes.VIDEO_MP4V, "ffmpeg7.1-mpeg4", allowFallback = true, ffmpegAvailable = true))
    }

    @Test
    fun bundledBuildDoesNotFallThroughToFfmpegForMpeg4() {
        val gate = VideoDecoderGate()
        assertEquals(false, ffmpegCanDecode(MimeTypes.VIDEO_MP4V))
        assertEquals(false, ffmpegCanDecode(MimeTypes.VIDEO_H264))
        assertEquals(
            VideoDecoderGate.SOFTWARE,
            gate.advance(MimeTypes.VIDEO_MP4V, "OMX.allwinner.video.decoder.mpeg4", allowFallback = true),
        )
        assertNull(gate.advance(MimeTypes.VIDEO_MP4V, "c2.android.mpeg4.decoder", allowFallback = true))
        val div3 = VideoDecoderGate()
        assertNull(div3.advance(MimeTypes.VIDEO_MP43, "c2.android.mpeg4.decoder", allowFallback = true))
    }

    @Test
    fun hardwarePreferenceDoesNotSwitchAndUnknownCodecStopsBeforeFfmpeg() {
        val gate = VideoDecoderGate()
        assertNull(gate.advance(MimeTypes.VIDEO_MP4V, "OMX.allwinner.video.decoder.mpeg4", allowFallback = false))
        assertEquals(VideoDecoderGate.SOFTWARE, gate.advance("video/mjpeg", "OMX.allwinner.video.decoder.mjpeg", allowFallback = true))
        assertNull(gate.advance("video/mjpeg", "c2.android.mjpeg.decoder", allowFallback = true))
    }

    @Test
    fun messageNamesTheCodecAndDoesNotSendMpeg4ToADeadEnd() {
        assertEquals("MPEG-4 (XviD/DivX)", codecLabel(MimeTypes.VIDEO_MP4V))
        assertEquals("MS-MPEG4 (DIV3)", codecLabel(MimeTypes.VIDEO_MP43))
        assertEquals(
            "error.decoder_codec_no_ffmpeg",
            decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.HARDWARE, VideoDecoderGate.DEFAULT),
        )
        assertEquals(
            "error.decoder_codec_no_ffmpeg",
            decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.FFMPEG, VideoDecoderGate.DEFAULT),
        )
        assertEquals(
            "error.decoder_codec_no_ffmpeg",
            decoderErrorKey(MimeTypes.VIDEO_MP43, DecoderPreference.AUTO, VideoDecoderGate.SOFTWARE),
        )
        assertEquals(
            "error.decoder_codec",
            decoderErrorKey(MimeTypes.VIDEO_H264, DecoderPreference.HARDWARE, VideoDecoderGate.DEFAULT, ffmpegAvailable = true),
        )
        assertEquals(
            "error.decoder_codec_ffmpeg",
            decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.FFMPEG, VideoDecoderGate.DEFAULT, ffmpegAvailable = true),
        )
        assertEquals(
            "error.decoder_codec_ffmpeg",
            decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.AUTO, VideoDecoderGate.FFMPEG, ffmpegAvailable = true),
        )
        assertEquals("error.decoder_codec_no_ffmpeg", decoderErrorKey("video/mjpeg", DecoderPreference.AUTO, VideoDecoderGate.SOFTWARE, ffmpegAvailable = false))
        assertEquals("error.decoder", decoderErrorKey(null, DecoderPreference.AUTO, VideoDecoderGate.DEFAULT))
        assertEquals(true, ffmpegMaps(MimeTypes.VIDEO_MP4V))
        assertEquals(true, ffmpegMaps(MimeTypes.VIDEO_MP43))
        assertEquals(false, ffmpegMaps("video/mjpeg"))
    }

    @Test
    fun unsupportedVideoIsAnErrorAndASupportedTrackIsNot() {
        val div3 = videoGroup(MimeTypes.VIDEO_MP43, C.FORMAT_UNSUPPORTED_SUBTYPE)
        assertEquals(MimeTypes.VIDEO_MP43, unsupportedVideoMime(listOf(div3), videoDisabled = false))
        assertNull(unsupportedVideoMime(listOf(div3), videoDisabled = true))
        val playing = videoGroup(MimeTypes.VIDEO_MP4V, C.FORMAT_HANDLED)
        assertNull(unsupportedVideoMime(listOf(playing), videoDisabled = false))
        val cover = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.IMAGE_JPEG).build()),
            false,
            intArrayOf(C.FORMAT_UNSUPPORTED_TYPE),
            booleanArrayOf(false),
        )
        assertNull(unsupportedVideoMime(listOf(cover), videoDisabled = false))
        val audio = Tracks.Group(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG).build()),
            false,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(true),
        )
        assertNull(unsupportedVideoMime(listOf(audio), videoDisabled = false))
    }

    private fun videoGroup(mime: String, support: Int) = Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(mime).build()),
        false,
        intArrayOf(support),
        booleanArrayOf(support == C.FORMAT_HANDLED),
    )

    @Test
    fun patchedFfmpegLibraryMapsMpeg4() {
        val method = Class.forName("io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary")
            .getDeclaredMethod("getCodecName", String::class.java)
        method.isAccessible = true
        assertEquals("mpeg4", method.invoke(null, MimeTypes.VIDEO_MP4V))
        assertEquals("msmpeg4v3", method.invoke(null, MimeTypes.VIDEO_MP43))
        assertEquals("msmpeg4v2", method.invoke(null, MimeTypes.VIDEO_MP42))
        assertEquals("h263", method.invoke(null, MimeTypes.VIDEO_H263))
        assertEquals("h264", method.invoke(null, MimeTypes.VIDEO_H264))
        assertEquals("mp3", method.invoke(null, MimeTypes.AUDIO_MPEG))
    }
}
