package tv.p2160.core.avi

import androidx.media3.common.C

/**
 * Заголовок AVI до того, как его видит Media3 1.11.1.
 *
 * Media3 ставит длительность как avih.dwTotalFrames * dwMicroSecPerFrame и потом берёт max()
 * с потоками, поэтому верный и более короткий video strh её не уменьшает.
 * strf до strh не получает тип дорожки. DIV3/DIV4 нет в карте FourCC, формат не создаётся.
 * Здесь заголовок поправляется в том же буфере, который экстрактор сейчас читает.
 */
internal data class AviVideoFact(
    val handler: String,
    val compression: String,
    val width: Int,
    val height: Int,
    val scale: Int,
    val rate: Int,
    val length: Int,
    val sampleSize: Int,
    val start: Int,
    val strfBeforeStrh: Boolean,
)

internal data class AviFacts(
    val videos: List<AviVideoFact>,
    val avihFrames: Int,
    val avihFrameUs: Int,
    val idx1VideoFrames: Int,
    val durationUs: Long,
    /** Битовый поток DivX 3: дорожку отдаём как video/divx3, не как MPEG-4 Part 2. */
    val divx3: Boolean,
)

internal object AviProbe {
    @Volatile
    var facts: AviFacts? = null

    fun clear() {
        facts = null
    }

    val durationUs: Long
        get() = facts?.durationUs?.takeIf { it > 0 } ?: C.TIME_UNSET

    val divx3: Boolean
        get() = facts?.divx3 == true

    val video: AviVideoFact?
        get() = facts?.videos?.firstOrNull()
}

internal object AviHeader {
    const val MIME_DIVX3 = "video/divx3"

    private val MEDIA3_VIDEO = setOf(
        "XVID", "xvid", "DIVX", "divx", "DX50", "FMP4", "MP42", "MP43",
        "H264", "AVC1", "avc1", "MJPG", "mjpg",
    )
    private val DIVX3 = setOf("DIV3", "div3", "DIV4", "div4", "3VID")

    /**
     * Длительность ролика. [videoSampleSize] не используется: у Tarkovsky там мусор 1036800.
     * Сначала video strh (dwLength * dwScale / dwRate), затем число кадров idx1, затем avih.
     */
    fun resolveDurationUs(
        videoLength: Int,
        videoScale: Int,
        videoRate: Int,
        videoSampleSize: Int,
        idx1VideoFrames: Int,
        avihFrames: Int,
        avihFrameUs: Int,
    ): Long {
        @Suppress("UNUSED_VARIABLE")
        val ignoredSampleSize = videoSampleSize
        if (videoLength > 0 && videoScale > 0 && videoRate > 0) {
            return videoLength.toLong() * videoScale.toLong() * 1_000_000L / videoRate
        }
        if (idx1VideoFrames > 0 && videoScale > 0 && videoRate > 0) {
            return idx1VideoFrames.toLong() * videoScale.toLong() * 1_000_000L / videoRate
        }
        if (idx1VideoFrames > 0 && avihFrameUs > 0) return idx1VideoFrames.toLong() * avihFrameUs
        if (avihFrames > 0 && avihFrameUs > 0) return avihFrames.toLong() * avihFrameUs
        return 0L
    }

    /** Тело hdrl (без четырёх байт 'hdrl'). null, если это не заголовок. */
    fun tryFixHdrl(data: ByteArray, offset: Int, length: Int): AviFacts? {
        if (length < 16 || offset < 0 || offset + length > data.size) return null
        if (!looksLikeHdrl(data, offset, length)) return null
        val end = offset + length
        var pos = offset
        var avihFrames = 0
        var avihFrameUs = 0
        val videos = ArrayList<AviVideoFact>()
        var divx3 = false
        while (pos + 8 <= end) {
            val id = fourcc(data, pos)
            val size = le32(data, pos + 4)
            if (size < 0 || pos + 8L + size > end) break
            val body = pos + 8
            when (id) {
                "avih" -> if (size >= 20) {
                    avihFrameUs = le32(data, body)
                    avihFrames = le32(data, body + 16)
                }
                "LIST" -> if (size >= 4 && fourcc(data, body) == "strl") {
                    val fixed = fixStrl(data, body + 4, size - 4)
                    if (fixed != null) {
                        videos += fixed.first
                        if (fixed.second) divx3 = true
                    }
                }
            }
            pos = body + size + (size and 1)
        }
        if (videos.isEmpty() && avihFrames == 0) return null
        val primary = videos.firstOrNull { it.scale > 0 && it.rate > 0 && it.length > 0 } ?: videos.firstOrNull()
        val duration = resolveDurationUs(
            videoLength = primary?.length ?: 0,
            videoScale = primary?.scale ?: 0,
            videoRate = primary?.rate ?: 0,
            videoSampleSize = primary?.sampleSize ?: 0,
            idx1VideoFrames = 0,
            avihFrames = avihFrames,
            avihFrameUs = avihFrameUs,
        )
        return AviFacts(videos, avihFrames, avihFrameUs, 0, duration, divx3)
    }

    /** Тело idx1. null, если буфер не похож на индекс. */
    fun countIdx1Video(data: ByteArray, offset: Int, length: Int): Int? {
        if (length < 64 || length % 16 != 0 || offset < 0 || offset + length > data.size) return null
        val sample = minOf(length, 16 * 8)
        var i = 0
        while (i < sample) {
            if (!chunkId(data, offset + i)) return null
            i += 16
        }
        var video = 0
        var at = offset
        val end = offset + length
        while (at + 16 <= end) {
            val c = data[at + 2].toInt().toChar()
            val d = data[at + 3].toInt().toChar()
            if (c == 'd' && (d == 'c' || d == 'b')) video++
            at += 16
        }
        return video
    }

    fun withIdx1(facts: AviFacts, frames: Int): AviFacts {
        if (frames <= 0) return facts
        val primary = facts.videos.firstOrNull()
        val duration = resolveDurationUs(
            videoLength = primary?.length ?: 0,
            videoScale = primary?.scale ?: 0,
            videoRate = primary?.rate ?: 0,
            videoSampleSize = primary?.sampleSize ?: 0,
            idx1VideoFrames = frames,
            avihFrames = facts.avihFrames,
            avihFrameUs = facts.avihFrameUs,
        )
        return facts.copy(idx1VideoFrames = frames, durationUs = duration)
    }

    private fun looksLikeHdrl(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 16 || fourcc(data, offset) != "avih") return false
        val size = le32(data, offset + 4)
        return size in 16..length
    }

    /** @return факт видео и признак DivX 3. */
    private fun fixStrl(data: ByteArray, start: Int, length: Int): Pair<AviVideoFact, Boolean>? {
        if (length < 8) return null
        val end = start + length
        val pieces = ArrayList<Piece>()
        var pos = start
        while (pos + 8 <= end) {
            val id = fourcc(data, pos)
            val size = le32(data, pos + 4)
            if (size < 0 || pos + 8L + size > end) break
            val next = pos + 8 + size + (size and 1)
            pieces += Piece(id, pos, next.coerceAtMost(end))
            pos = next
        }
        val strhAt = pieces.indexOfFirst { it.id == "strh" }
        val strfAt = pieces.indexOfFirst { it.id == "strf" }
        val swapped = strhAt >= 0 && strfAt >= 0 && strfAt < strhAt
        val header = if (strhAt >= 0) readStrh(data, pieces[strhAt].from) else null
        val format = if (strfAt >= 0) readStrf(data, pieces[strfAt].from) else null
        if (header == null) {
            val compression = format?.compression.orEmpty()
            if (compression !in MEDIA3_VIDEO && compression !in DIVX3) return null
        } else if (header.type != "vids") {
            return null
        }
        var compression = format?.compression.orEmpty()
        var divx3 = compression in DIVX3 || header?.handler in DIVX3
        val known = compression in MEDIA3_VIDEO
        if (!known) {
            val replacement = when {
                divx3 -> "MP43"
                header?.handler in MEDIA3_VIDEO -> header?.handler?.let { canonical(it) }
                header?.handler?.lowercase() == "xvid" -> "XVID"
                header?.handler?.lowercase() == "divx" -> "DIVX"
                else -> null
            }
            if (replacement != null && format != null) {
                writeFourcc(data, format.compressionAt, replacement)
                compression = replacement
            }
        } else if (divx3 && format != null) {
            writeFourcc(data, format.compressionAt, "MP43")
            compression = "MP43"
        }
        if (header == null && format == null) return null
        if (swapped) swap(data, pieces[strfAt], pieces[strhAt])
        val fact = AviVideoFact(
            handler = header?.handler.orEmpty(),
            compression = compression,
            width = format?.width ?: 0,
            height = format?.height ?: 0,
            scale = header?.scale ?: 0,
            rate = header?.rate ?: 0,
            length = header?.length ?: 0,
            sampleSize = header?.sampleSize ?: 0,
            start = header?.start ?: 0,
            strfBeforeStrh = swapped,
        )
        return fact to divx3
    }

    private fun canonical(handler: String): String = when (handler.lowercase()) {
        "xvid" -> "XVID"
        "divx" -> "DIVX"
        "mp43", "div3", "div4" -> "MP43"
        "mp42" -> "MP42"
        "dx50" -> "DX50"
        "fmp4" -> "FMP4"
        else -> handler
    }

    private data class Strh(
        val type: String,
        val handler: String,
        val scale: Int,
        val rate: Int,
        val start: Int,
        val length: Int,
        val sampleSize: Int,
    )

    private data class Strf(val width: Int, val height: Int, val compression: String, val compressionAt: Int)

    private fun readStrh(data: ByteArray, chunk: Int): Strh? {
        val size = le32(data, chunk + 4)
        if (size < 32) return null
        val body = chunk + 8
        return Strh(
            type = fourcc(data, body),
            handler = fourcc(data, body + 4),
            scale = le32(data, body + 20),
            rate = le32(data, body + 24),
            start = if (size >= 32) le32(data, body + 28) else 0,
            length = if (size >= 36) le32(data, body + 32) else 0,
            sampleSize = if (size >= 48) le32(data, body + 44) else 0,
        )
    }

    private fun readStrf(data: ByteArray, chunk: Int): Strf? {
        val size = le32(data, chunk + 4)
        if (size < 20) return null
        val body = chunk + 8
        return Strf(le32(data, body + 4), le32(data, body + 8), fourcc(data, body + 16), body + 16)
    }

    private fun swap(data: ByteArray, first: Piece, second: Piece) {
        val a = data.copyOfRange(first.from, first.to)
        val middle = data.copyOfRange(first.to, second.from)
        val b = data.copyOfRange(second.from, second.to)
        var at = first.from
        b.copyInto(data, at)
        at += b.size
        middle.copyInto(data, at)
        at += middle.size
        a.copyInto(data, at)
    }

    private data class Piece(val id: String, val from: Int, val to: Int)

    private fun chunkId(data: ByteArray, at: Int): Boolean {
        val a = data[at].toInt().toChar()
        val b = data[at + 1].toInt().toChar()
        val c = data[at + 2].toInt().toChar()
        val d = data[at + 3].toInt().toChar()
        if (!a.isDigit() || !b.isDigit()) return false
        return (c == 'd' && (d == 'c' || d == 'b')) || (c == 'w' && d == 'b') || (c == 'p' && d == 'c')
    }

    private fun fourcc(data: ByteArray, at: Int): String {
        if (at + 4 > data.size) return ""
        return String(data, at, 4, Charsets.ISO_8859_1)
    }

    private fun writeFourcc(data: ByteArray, at: Int, value: String) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        if (bytes.size == 4 && at + 4 <= data.size) bytes.copyInto(data, at)
    }

    private fun le32(data: ByteArray, at: Int): Int {
        if (at + 4 > data.size) return 0
        return (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
    }
}
