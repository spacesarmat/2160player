package tv.p2160.core.engine

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.p2160.core.settings.DecoderPreference

class VideoDecodeFallbackTest {
    @Test
    fun autoStepsFromHardwareToSoftwareThenFfmpeg() {
        val gate = VideoDecoderGate()
        assertEquals(VideoDecoderGate.SOFTWARE, gate.advance(MimeTypes.VIDEO_MP4V, "OMX.allwinner.video.decoder.mpeg4", allowFallback = true))
        assertEquals(VideoDecoderGate.FFMPEG, gate.advance(MimeTypes.VIDEO_MP4V, "c2.android.mpeg4.decoder", allowFallback = true))
        assertNull(gate.advance(MimeTypes.VIDEO_MP4V, "ffmpeg7.1-mpeg4", allowFallback = true))
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
        assertEquals("error.decoder_codec", decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.HARDWARE, VideoDecoderGate.DEFAULT))
        assertEquals("error.decoder_codec_ffmpeg", decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.FFMPEG, VideoDecoderGate.DEFAULT))
        assertEquals("error.decoder_codec_ffmpeg", decoderErrorKey(MimeTypes.VIDEO_MP4V, DecoderPreference.AUTO, VideoDecoderGate.FFMPEG))
        assertEquals("error.decoder_codec_no_ffmpeg", decoderErrorKey("video/mjpeg", DecoderPreference.AUTO, VideoDecoderGate.SOFTWARE))
        assertEquals("error.decoder", decoderErrorKey(null, DecoderPreference.AUTO, VideoDecoderGate.DEFAULT))
        assertEquals(true, ffmpegMaps(MimeTypes.VIDEO_MP4V))
        assertEquals(true, ffmpegMaps(MimeTypes.VIDEO_MP43))
        assertEquals(false, ffmpegMaps("video/mjpeg"))
    }

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
        assertEquals("vc1", method.invoke(null, MimeTypes.VIDEO_VC1))
        assertEquals("wmv3", method.invoke(null, "video/x-ms-wmv"))
        assertEquals("wmv1", method.invoke(null, "video/wmv1"))
        assertEquals("wmv2", method.invoke(null, "video/wmv2"))
        assertEquals(true, ffmpegMaps(MimeTypes.VIDEO_VC1))
        assertEquals(true, ffmpegMaps("video/x-ms-wmv"))
    }
}
