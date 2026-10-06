package tv.p2160.core.bluray

/** Элемент плейлиста (PlayItem): отрезок клипа [inTime45k, outTime45k) в тиках 45 кГц. */
data class MplsPlayItem(
    val clipName: String,
    val codecId: String,
    val connectionCondition: Int,
    val stcId: Int,
    val inTime45k: Long,
    val outTime45k: Long,
    /** Число ракурсов (1 — обычный элемент); используется ракурс 1 — [clipName]. */
    val angleCount: Int,
    val videoStreams: List<BlurayStream>,
    val audioStreams: List<BlurayStream>,
    val pgStreams: List<BlurayStream>,
    val igStreams: List<BlurayStream>,
    val secondaryAudioCount: Int,
    val secondaryVideoCount: Int,
) {
    val duration45k: Long get() = (outTime45k - inTime45k).coerceAtLeast(0)
}

/** Метка плейлиста; [type] 1 — начало главы (entry mark), 2 — link point. */
data class MplsMark(val type: Int, val playItemRef: Int, val time45k: Long, val entryPid: Int, val duration45k: Long)

data class MplsPlaylist(
    val version: String,
    val items: List<MplsPlayItem>,
    val subPathCount: Int,
    val marks: List<MplsMark>,
)

/** Разбор BDMV/PLAYLIST/xxxxx.mpls (MPLS 0100/0200/0300). */
object MplsParser {
    const val MARK_ENTRY = 1

    fun parse(data: ByteArray): MplsPlaylist {
        val r = BeReader(data)
        if (r.ascii(4) != "MPLS") throw BlurayFormatException("Not an MPLS file")
        val version = r.ascii(4)
        val playListStart = r.u32().toInt()
        val marksStart = r.u32().toInt()

        r.seek(playListStart)
        r.u32() // length
        r.skip(2)
        val itemCount = r.u16()
        val subPathCount = r.u16()
        val items = ArrayList<MplsPlayItem>(itemCount)
        repeat(itemCount) {
            val len = r.u16()
            val start = r.pos
            val end = start + len
            if (end > data.size) throw BlurayFormatException("PlayItem out of bounds")
            items += parseItem(BeReader(data, start, end))
            r.seek(end)
        }

        val marks = if (marksStart in 1 until data.size) parseMarks(data, marksStart) else emptyList()
        return MplsPlaylist(version, items, subPathCount, marks)
    }

    private fun parseItem(r: BeReader): MplsPlayItem {
        val clipName = r.ascii(5)
        val codecId = r.ascii(4)
        val flags = r.u16()
        val isMultiAngle = (flags ushr 4) and 1 == 1
        val connection = flags and 0xF
        val stcId = r.u8()
        val inTime = r.u32()
        val outTime = r.u32()
        r.skip(8) // UO_mask_table
        r.skip(1) // random_access_flag
        r.skip(1 + 2) // still_mode, still_time
        var angles = 1
        if (isMultiAngle) {
            angles = r.u8().coerceAtLeast(1)
            r.skip(1)
            r.skip((angles - 1) * 10) // clip name + codec + STC id других ракурсов
        }
        return parseStn(r, clipName, codecId, connection, stcId, inTime, outTime, angles)
    }

    private fun parseStn(
        r: BeReader, clip: String, codec: String, cc: Int, stc: Int, inTime: Long, outTime: Long, angles: Int,
    ): MplsPlayItem {
        val len = r.u16()
        val end = r.pos + len
        if (end > r.limit) throw BlurayFormatException("STN table out of bounds")
        r.skip(2)
        val nVideo = r.u8()
        val nAudio = r.u8()
        val nPg = r.u8()
        val nIg = r.u8()
        val nSecAudio = r.u8()
        val nSecVideo = r.u8()
        val nPipPg = r.u8()
        r.skip(5)
        val sr = BeReader(r.data, r.pos, end)
        val video = List(nVideo) { parseStream(sr) }
        val audio = List(nAudio) { parseStream(sr) }
        val pg = List(nPg + nPipPg) { parseStream(sr) }.take(nPg)
        val ig = List(nIg) { parseStream(sr) }
        return MplsPlayItem(
            clip, codec, cc, stc, inTime, outTime, angles,
            video, audio, pg, ig, nSecAudio, nSecVideo,
        )
    }

    /** stream_entry + stream_attributes. */
    private fun parseStream(r: BeReader): BlurayStream {
        val entryLen = r.u8()
        val entryEnd = r.pos + entryLen
        val pid = when (r.u8()) {
            1 -> r.u16() // ref_to_stream_PID_of_mainClip
            2, 4 -> { r.skip(2); r.u16() }
            3 -> { r.skip(1); r.u16() }
            else -> 0
        }
        r.seek(entryEnd)
        val attrLen = r.u8()
        val attrEnd = r.pos + attrLen
        if (attrEnd > r.limit) throw BlurayFormatException("Stream attributes out of bounds")
        val stream = StreamCoding.parseAttributes(BeReader(r.data, r.pos, attrEnd), pid, attrEnd)
        r.seek(attrEnd)
        return stream
    }

    private fun parseMarks(data: ByteArray, start: Int): List<MplsMark> {
        val r = BeReader(data, start)
        r.u32() // length
        val count = r.u16()
        val result = ArrayList<MplsMark>(count)
        repeat(count) {
            if (r.pos + 14 > r.limit) return result
            r.skip(1)
            val type = r.u8()
            val item = r.u16()
            val time = r.u32()
            val pid = r.u16()
            val duration = r.u32()
            result += MplsMark(type, item, time, pid, duration)
        }
        return result
    }
}
