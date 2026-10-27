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
        // Есть общий ARM-APK - ARM-устройства берут его, x86 - свой.
        val withArm = names + "2160player-0.2.0-arm-release.apk" + "2160player-0.2.0-x86_64-release.apk"
        assertEquals("2160player-0.2.0-arm-release.apk", Updater.pickAsset(withArm, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("2160player-0.2.0-arm-release.apk", Updater.pickAsset(withArm, listOf("armeabi-v7a")))
        assertEquals("2160player-0.2.0-x86_64-release.apk", Updater.pickAsset(withArm, listOf("x86_64")))
    }

    @Test
    fun readsTagFromLatestRedirect() {
        assertEquals(
            "v0.2.11",
            Updater.tagFromLatestLocation("https://github.com/spacesarmat/2160player/releases/tag/v0.2.11"),
        )
        assertEquals(
            "v1.0.0",
            Updater.tagFromLatestLocation("https://github.com/spacesarmat/2160player/releases/tag/v1.0.0/"),
        )
        assertEquals(
            "v0.2.9",
            Updater.tagFromLatestLocation("/spacesarmat/2160player/releases/tag/v0.2.9?welcome=1"),
        )
        assertNull(Updater.tagFromLatestLocation(null))
        assertNull(Updater.tagFromLatestLocation(""))
        assertNull(Updater.tagFromLatestLocation("https://github.com/spacesarmat/2160player/releases/latest"))
    }

    @Test
    fun buildsAssetUrlsInPickOrder() {
        val armAbis = listOf("arm64-v8a", "armeabi-v7a")
        val armNames = Updater.assetProbeNames("0.2.11", armAbis)
        assertEquals(
            listOf(
                "2160player-0.2.11-arm-release.apk",
                "2160player-0.2.11-arm64-v8a-release.apk",
                "2160player-0.2.11-armeabi-v7a-release.apk",
                "2160player-0.2.11-universal-release.apk",
            ),
            armNames,
        )
        assertEquals(armNames.first(), Updater.pickAsset(armNames, armAbis))
        assertEquals(
            "https://github.com/spacesarmat/2160player/releases/download/v0.2.11/2160player-0.2.11-arm-release.apk",
            Updater.assetDownloadUrl("0.2.11", "arm"),
        )
        val x86 = Updater.assetProbeNames("0.2.11", listOf("x86_64"))
        assertEquals(
            listOf(
                "2160player-0.2.11-x86_64-release.apk",
                "2160player-0.2.11-universal-release.apk",
            ),
            x86,
        )
        assertEquals(x86.first(), Updater.pickAsset(x86, listOf("x86_64")))
        assertEquals(
            "https://github.com/spacesarmat/2160player/releases/download/v0.2.11/2160player-0.2.11-universal-release.apk",
            Updater.releaseAssetUrl("0.2.11", "2160player-0.2.11-universal-release.apk"),
        )
    }

    @Test
    fun retryMinutesFollowTheRateLimitReset() {
        val now = 1_700_000_000L
        assertEquals(2, Updater.retryMinutes(now + 90, null, now))
        assertEquals(1, Updater.retryMinutes(now, null, now))
        assertEquals(1, Updater.retryMinutes(null, 30, now))
        assertEquals(60, Updater.retryMinutes(null, null, now))
    }

    @Test
    fun stripsMarkdownFromNotes() {
        val md = listOf("**Title**", "- `arm64` - phones", "see [API](docs/API.md)").joinToString("\n")
        assertEquals(listOf("Title", "• arm64 - phones", "see API").joinToString("\n"), Updater.plainText(md))
    }
}
