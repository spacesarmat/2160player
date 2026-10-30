package tv.p2160.core.engine

import tv.p2160.core.api.Chapter
import tv.p2160.core.bluray.RandomAccessSource
import tv.p2160.core.bluray.readBytes
import java.io.EOFException
import java.io.IOException

/**
 * Главы Matroska/WebM из самого файла.
 * nextlib на armeabi-v7a передаёт время главы как 32-битный `long` в JNI, где ждётся `jlong`.
 * На Dune (Android 9, 32 бита) список из-за этого пустой, на arm64 те же главы есть.
 * Здесь время читается как наносекунды спецификации, без этого вызова.
 */
internal object MatroskaChapters {
    private const val ID_EBML = 0x1A45DFA3
    private const val ID_SEGMENT = 0x18538067
    private const val ID_SEEK_HEAD = 0x114D9B74
    private const val ID_SEEK = 0x4DBB
    private const val ID_SEEK_ID = 0x53AB
    private const val ID_SEEK_POSITION = 0x53AC
    private const val ID_CLUSTER = 0x1F43B675
    private const val ID_CHAPTERS = 0x1043A770
    private const val ID_EDITION = 0x45B9
    private const val ID_ATOM = 0xB6
    private const val ID_TIME_START = 0x91
    private const val ID_TIME_END = 0x92
    private const val ID_DISPLAY = 0x80
    private const val ID_STRING = 0x85
    private const val ID_HIDDEN = 0x98

    fun read(source: RandomAccessSource): List<Chapter> = try {
        if (source.size < 8) return emptyList()
        val cursor = Cursor(source)
        val header = cursor.element(0) ?: return emptyList()
        if (header.id != ID_EBML || header.sizeUnknown) return emptyList()
        val segment = cursor.element(header.end) ?: return emptyList()
        if (segment.id != ID_SEGMENT) return emptyList()
        val chapters = findChapters(cursor, segment) ?: return emptyList()
        finish(atoms(cursor, chapters))
    } catch (_: IOException) {
        emptyList()
    }

    private fun findChapters(cursor: Cursor, segment: Element): Element? {
        var pos = segment.data
        val end = if (segment.sizeUnknown) cursor.source.size else segment.end
        val seeks = HashMap<Int, Long>()
        repeat(48) {
            if (pos + 2 >= end) return@repeat
            val el = cursor.element(pos) ?: return null
            when (el.id) {
                ID_CHAPTERS -> return el
                ID_SEEK_HEAD -> readSeeks(cursor, el, seeks)
                ID_CLUSTER -> return seekChapters(cursor, segment.data, seeks)
            }
            if (el.sizeUnknown) return seekChapters(cursor, segment.data, seeks)
            pos = el.end
        }
        return seekChapters(cursor, segment.data, seeks)
    }

    private fun seekChapters(cursor: Cursor, segmentData: Long, seeks: Map<Int, Long>): Element? {
        val at = seeks[ID_CHAPTERS] ?: return null
        val el = cursor.element(segmentData + at) ?: return null
        return el.takeIf { it.id == ID_CHAPTERS }
    }

    private fun readSeeks(cursor: Cursor, head: Element, into: MutableMap<Int, Long>) {
        for (seek in children(cursor, head)) {
            if (seek.id != ID_SEEK) continue
            var id = 0
            var position = -1L
            for (field in children(cursor, seek)) {
                when (field.id) {
                    ID_SEEK_ID -> id = readIdBytes(cursor, field)
                    ID_SEEK_POSITION -> position = readUint(cursor, field)
                }
            }
            if (id != 0 && position >= 0) into[id] = position
        }
    }

    private fun atoms(cursor: Cursor, parent: Element): List<Raw> {
        val editions = children(cursor, parent).filter { it.id == ID_EDITION }
        val roots = if (editions.isNotEmpty()) {
            children(cursor, editions.first()).filter { it.id == ID_ATOM }
        } else {
            children(cursor, parent).filter { it.id == ID_ATOM }
        }
        return roots.flatMap { atom(cursor, it) }
    }

    private fun atom(cursor: Cursor, el: Element): List<Raw> {
        var startNs = -1L
        var endNs = -1L
        var title: String? = null
        var hidden = false
        val nested = ArrayList<Element>()
        for (child in children(cursor, el)) {
            when (child.id) {
                ID_TIME_START -> startNs = readUint(cursor, child)
                ID_TIME_END -> endNs = readUint(cursor, child)
                ID_HIDDEN -> hidden = readUint(cursor, child) != 0L
                ID_DISPLAY -> if (title == null) title = chapString(cursor, child)
                ID_ATOM -> nested += child
            }
        }
        val own = if (!hidden && startNs >= 0) listOf(Raw(startNs, endNs, title)) else emptyList()
        return own + nested.flatMap { atom(cursor, it) }
    }

    private fun chapString(cursor: Cursor, display: Element): String? =
        children(cursor, display).firstOrNull { it.id == ID_STRING }?.let { field ->
            cursor.bytes(field.data, field.payloadSize)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun finish(raw: List<Raw>): List<Chapter> = raw.mapIndexed { index, item ->
        val endNs = if (item.endNs >= 0) item.endNs else raw.getOrNull(index + 1)?.startNs ?: (item.startNs + 1_000_000)
        Chapter(item.title, item.startNs / 1_000_000, endNs / 1_000_000)
    }.filter { it.endMs > it.startMs }

    private fun children(cursor: Cursor, parent: Element): List<Element> {
        if (parent.payloadSize < 0 || parent.payloadSize > MAX_ELEMENT) return emptyList()
        val out = ArrayList<Element>()
        var pos = parent.data
        val end = parent.end
        while (pos + 2 <= end && out.size < 256) {
            val child = cursor.element(pos) ?: break
            if (!child.sizeUnknown && child.end > end) break
            out += child
            if (child.sizeUnknown) break
            pos = child.end
        }
        return out
    }

    private fun readUint(cursor: Cursor, el: Element): Long {
        val bytes = cursor.bytes(el.data, el.payloadSize) ?: return -1
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
        return value
    }

    private fun readIdBytes(cursor: Cursor, el: Element): Int {
        val bytes = cursor.bytes(el.data, el.payloadSize) ?: return 0
        var value = 0
        for (b in bytes) value = (value shl 8) or (b.toInt() and 0xFF)
        return value
    }

    private class Cursor(val source: RandomAccessSource) {
        fun element(position: Long): Element? {
            val header = peek(position, 16) ?: return null
            if (header.size < 2) return null
            val (id, idLen) = idAt(header, 0) ?: return null
            if (idLen >= header.size) return null
            val (size, sizeLen, unknown) = sizeAt(header, idLen) ?: return null
            val data = position + idLen + sizeLen
            val end = if (unknown) source.size else data + size
            val payload = if (unknown || size > MAX_ELEMENT) -1 else size.toInt()
            return Element(id, data, end, payload, unknown)
        }

        fun bytes(position: Long, length: Int): ByteArray? {
            if (length < 0 || length > MAX_ELEMENT) return null
            if (length == 0) return ByteArray(0)
            return peek(position, length)?.takeIf { it.size == length }
        }

        private fun peek(position: Long, length: Int): ByteArray? {
            if (position < 0 || position >= source.size) return null
            val n = minOf(length.toLong(), source.size - position).toInt()
            if (n <= 0) return null
            return try {
                source.readBytes(position, n)
            } catch (_: EOFException) {
                null
            }
        }
    }

    private data class Element(
        val id: Int,
        val data: Long,
        val end: Long,
        val payloadSize: Int,
        val sizeUnknown: Boolean,
    )

    private data class Raw(val startNs: Long, val endNs: Long, val title: String?)

    private fun idAt(buf: ByteArray, offset: Int): Pair<Int, Int>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        var length = 1
        var mask = 0x80
        while (length <= 4 && first and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (mask == 0 || offset + length > buf.size) return null
        var value = 0
        for (i in 0 until length) value = (value shl 8) or (buf[offset + i].toInt() and 0xFF)
        return value to length
    }

    private fun sizeAt(buf: ByteArray, offset: Int): Triple<Long, Int, Boolean>? {
        if (offset >= buf.size) return null
        val first = buf[offset].toInt() and 0xFF
        var length = 1
        var mask = 0x80
        while (length <= 8 && first and mask == 0) {
            mask = mask shr 1
            length++
        }
        if (mask == 0 || offset + length > buf.size) return null
        var value = (first and (mask - 1)).toLong()
        for (i in 1 until length) value = (value shl 8) or (buf[offset + i].toInt() and 0xFF).toLong()
        val unknown = value == (1L shl (7 * length)) - 1
        return Triple(value, length, unknown)
    }

    private const val MAX_ELEMENT = 1_048_576
}
