package tv.p2160.core

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.p2160.core.engine.DolbyVisionFallback

class DolbyVisionFallbackTest {

    @Test
    fun parsesProfile() {
        assertEquals(7, DolbyVisionFallback.profile("dvhe.07.06"))
        assertEquals(8, DolbyVisionFallback.profile("dvh1.08.06"))
        assertNull(DolbyVisionFallback.profile(null))
        assertNull(DolbyVisionFallback.profile("dvhe"))
    }

    @Test
    fun picksCompatibleBaseLayer() {
        assertEquals(MimeTypes.VIDEO_H265, DolbyVisionFallback.baseMimeType("dvhe.07.06", 7))
        assertEquals(MimeTypes.VIDEO_H265, DolbyVisionFallback.baseMimeType("dvh1.08.06", 8))
        assertEquals(MimeTypes.VIDEO_H264, DolbyVisionFallback.baseMimeType("dvav.09.05", 9))
        assertEquals(MimeTypes.VIDEO_AV1, DolbyVisionFallback.baseMimeType("dav1.10.06", 10))
        // Профиль 5 без совместимого слоя — не подменяем.
        assertNull(DolbyVisionFallback.baseMimeType("dvhe.05.06", 5))
    }
}
