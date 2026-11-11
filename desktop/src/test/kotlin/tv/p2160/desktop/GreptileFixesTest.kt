package tv.p2160.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.p2160.desktop.media.ByteSource
import tv.p2160.desktop.media.LibraryStore
import tv.p2160.desktop.media.RangeServer
import tv.p2160.desktop.media.SmbSaved
import tv.p2160.desktop.media.ReaderSet
import tv.p2160.desktop.media.extractNative
import tv.p2160.desktop.media.shareListAfterUp
import tv.p2160.desktop.player.loadFileCommand
import tv.p2160.desktop.player.numericLocaleCategory
import tv.p2160.desktop.player.shouldCopyFrame
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class GreptileFixesTest {
    @Test
    fun loadFileDoesNotPutPauseInTheIndexSlot() {
        assertEquals(listOf("loadfile", "/tmp/a.mkv", "replace"), loadFileCommand("/tmp/a.mkv"))
    }

    @Test
    fun numericLocaleCategoryMatchesTheCLibrary() {
        assertEquals(1, numericLocaleCategory("Linux"))
        assertEquals(4, numericLocaleCategory("Mac OS X"))
        assertEquals(4, numericLocaleCategory("Windows 11"))
    }

    @Test
    fun nativeLibraryIsNotTakenFromAFixedSharedDirectory() {
        val poisoned = java.io.File(System.getProperty("java.io.tmpdir"), "p2160-libtorrent4j")
        poisoned.mkdirs()
        val planted = java.io.File(poisoned, "libtorrent4j.so")
        planted.writeBytes(byteArrayOf(1, 2, 3))
        val out = extractNative(byteArrayOf(9, 9, 9, 9), "libtorrent4j.so")
        assertEquals(listOf<Byte>(9, 9, 9, 9), out.readBytes().toList())
        val parent = checkNotNull(out.parentFile)
        assertFalse(parent.name == "p2160-libtorrent4j")
        assertTrue(parent.name.startsWith("p2160-libtorrent4j-"))
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            val perms = Files.getPosixFilePermissions(out.toPath())
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ))
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ))
        }
        out.parentFile?.deleteRecursively()
        planted.delete()
    }

    @Test
    fun leavingAShareRestoresTheShareList() {
        assertEquals(listOf("movies", "music"), shareListAfterUp("", listOf("movies", "music")))
        assertEquals(null, shareListAfterUp("films\\season", listOf("movies")))
    }

    @Test
    fun libraryFileIsCreatedOwnerOnly() {
        val dir = Files.createTempDirectory("p2160-lib").toFile()
        val store = LibraryStore(dir)
        store.rememberServer(SmbSaved("host", "user", "secret", "workgroup"))
        val file = java.io.File(dir, "library.json")
        assertTrue(file.readText().contains("secret"))
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            val perms = Files.getPosixFilePermissions(file.toPath())
            assertFalse(perms.contains(PosixFilePermission.GROUP_READ))
            assertFalse(perms.contains(PosixFilePermission.OTHERS_READ))
            val dirPerms = Files.getPosixFilePermissions(dir.toPath())
            assertFalse(dirPerms.contains(PosixFilePermission.OTHERS_EXECUTE))
        }
        dir.deleteRecursively()
    }

    @Test
    fun closingTheTorrentSourceClosesItsReaders() {
        val set = ReaderSet()
        val first = Flag()
        val second = Flag()
        set.track(first)
        set.track(second)
        set.close()
        assertTrue(first.closed)
        assertTrue(second.closed)
        val late = Flag()
        set.track(late)
        assertTrue(late.closed)
    }

    @Test
    fun finishedReaderLeavesTheSet() {
        val set = ReaderSet()
        val done = Flag()
        val active = Flag()
        set.track(done)
        set.track(active)
        done.close()
        set.release(done)
        assertEquals(1, set.size())
        set.close()
        assertTrue(active.closed)
        assertEquals(0, set.size())
    }

    @Test
    fun pausedPlaybackStillCopiesAFrameWhenMpvAsks() {
        assertFalse(shouldCopyFrame(paused = true, hasFrame = true, positionChanged = false, redrawRequested = false))
        assertTrue(shouldCopyFrame(paused = true, hasFrame = true, positionChanged = false, redrawRequested = true))
        assertTrue(shouldCopyFrame(paused = false, hasFrame = true, positionChanged = false, redrawRequested = false))
    }

    @Test
    fun rangeServerUsesAFreshReaderPerRequest() {
        val server = RangeServer()
        try {
            val parent = IndependentSource()
            val url = server.publish(parent)
            val body = URI(url).toURL().openStream().use { it.readBytes() }
            assertEquals(1, parent.opened)
            assertEquals(4, body.size)
        } finally {
            server.close()
        }
    }
}

private class Flag : AutoCloseable {
    var closed = false
    override fun close() {
        closed = true
    }
}

private class IndependentSource : ByteSource {
    var opened = 0
    override val length = 4L
    override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int = error("shared reader")
    override fun openReader(): ByteSource {
        opened++
        return object : ByteSource {
            override val length = 4L
            override fun read(offset: Long, dst: ByteArray, dstOff: Int, len: Int): Int {
                if (offset >= 4) return -1
                val n = minOf(len.toLong(), 4 - offset).toInt()
                for (i in 0 until n) dst[dstOff + i] = (offset + i).toByte()
                return n
            }
            override fun close() = Unit
        }
    }
    override fun close() = Unit
}
