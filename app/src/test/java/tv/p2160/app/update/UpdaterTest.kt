package tv.p2160.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("1.0", "0.9.9"))
        assertFalse(Updater.isNewer("0.1.0", "0.1.0"))
        assertFalse(Updater.isNewer("0.1.0-beta", "0.1.0"))
        assertFalse(Updater.isNewer("0.0.9", "0.1.0"))
    }

    @Test
    fun picksApkForDeviceAbi() {
        val names = listOf(
            "2160player-0.2.0-arm64-v8a-release.apk",
            "2160player-0.2.0-armeabi-v7a-release.apk",
            "2160player-0.2.0-universal-release.apk",
        )
        assertEquals(names[0], Updater.pickAsset(names, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(names[1], Updater.pickAsset(names, listOf("armeabi-v7a", "armeabi")))
        assertEquals(names[2], Updater.pickAsset(names, listOf("x86_64")))
        assertNull(Updater.pickAsset(listOf("notes.txt"), listOf("x86_64")))
    }
}
