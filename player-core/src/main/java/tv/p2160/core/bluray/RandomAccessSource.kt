package tv.p2160.core.bluray

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Источник с произвольным доступом (файл, ISO по SMB/HTTP, участок другого источника).
 * Чтение блокирующее и потокобезопасное: позиция передаётся явно.
 */
interface RandomAccessSource : Closeable {
    /** Размер в байтах. */
    val size: Long

    /**
     * Читает до [length] байт с позиции [position]. Может вернуть меньше запрошенного;
     * -1 — конец данных.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/** Читает ровно [length] байт или бросает [EOFException]. */
fun RandomAccessSource.readFully(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
    var done = 0
    while (done < length) {
        val n = read(position + done, buffer, offset + done, length - done)
        if (n <= 0) throw EOFException("Unexpected end of data at ${position + done}")
        done += n
    }
}

/** Читает [length] байт с [position] в новый массив. */
fun RandomAccessSource.readBytes(position: Long, length: Int): ByteArray =
    ByteArray(length).also { readFully(position, it) }

/** Локальный файл. */
class FileRandomAccessSource(file: File) : RandomAccessSource {
    private val raf = RandomAccessFile(file, "r")
    private val channel: FileChannel = raf.channel
    override val size: Long = raf.length()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun close() = raf.close()
}

/** Массив в памяти (данные, встроенные в ICB, тесты). */
class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() {}
}

/**
 * Кусок файла на диске: [length] байт с [position] в родительском источнике;
 * position = -1 — незаписанный участок, читается нулями.
 */
data class DiscExtent(val position: Long, val length: Long)

/**
 * Файл, собранный из последовательных экстентов родителя (фрагментированный m2ts в ISO).
 * Родителя не закрывает — он общий для всех файлов образа.
 */
class ExtentRandomAccessSource(
    private val parent: RandomAccessSource,
    val extents: List<DiscExtent>,
) : RandomAccessSource {
    // Логическое начало каждого экстента — для бинарного поиска.
    private val starts = LongArray(extents.size)
    override val size: Long

    init {
        var acc = 0L
        extents.forEachIndexed { i, e -> starts[i] = acc; acc += e.length }
        size = acc
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0 || position >= size) return -1
        var i = starts.binarySearch(position)
        if (i < 0) i = -i - 2
        val ext = extents[i]
        val inExt = position - starts[i]
        val n = minOf(length.toLong(), ext.length - inExt).toInt()
        if (ext.position < 0) {
            buffer.fill(0, offset, offset + n)
            return n
        }
        return parent.read(ext.position + inExt, buffer, offset, n)
    }

    override fun close() {}
}

/**
 * Кэш поверх медленного источника (SMB/HTTP): LRU блоков по [blockSize] байт.
 * При промахе читает сразу [readAheadBlocks] блоков одним запросом — меньше обращений по сети.
 * Закрывает обёрнутый источник.
 */
class CachedRandomAccessSource(
    private val upstream: RandomAccessSource,
    private val blockSize: Int = 64 * 1024,
    private val maxBlocks: Int = 64,
    private val readAheadBlocks: Int = 4,
) : RandomAccessSource {
    override val size: Long get() = upstream.size

    private val cache = object : LinkedHashMap<Long, ByteArray>(maxBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > maxBlocks
    }

    init {
        require(blockSize > 0 && maxBlocks > 0 && readAheadBlocks in 1..maxBlocks)
    }

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0 || position >= size) return -1
        val index = position / blockSize
        val block = cache[index] ?: fetch(index)
        val inBlock = (position - index * blockSize).toInt()
        if (inBlock >= block.size) return -1
        val n = minOf(length, block.size - inBlock)
        System.arraycopy(block, inBlock, buffer, offset, n)
        return n
    }

    private fun fetch(first: Long): ByteArray {
        val start = first * blockSize
        val bytes = minOf(readAheadBlocks.toLong() * blockSize, size - start).toInt()
        val tmp = ByteArray(bytes)
        var done = 0
        while (done < bytes) {
            val n = upstream.read(start + done, tmp, done, bytes - done)
            if (n <= 0) break
            done += n
        }
        if (done == 0) throw IOException("Read failed at $start")
        var idx = first
        var off = 0
        while (off < done) {
            val len = minOf(blockSize, done - off)
            // Неполный блок кладём, только если это конец файла — иначе перечитаем позже.
            if (len == blockSize || start + off + len == size) cache[idx] = tmp.copyOfRange(off, off + len)
            else if (idx == first) return tmp.copyOfRange(off, off + len)
            idx++
            off += len
        }
        return cache[first] ?: tmp.copyOfRange(0, minOf(blockSize, done))
    }

    override fun close() {
        synchronized(this) { cache.clear() }
        upstream.close()
    }
}
