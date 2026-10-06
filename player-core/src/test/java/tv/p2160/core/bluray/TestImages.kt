package tv.p2160.core.bluray

import java.io.ByteArrayOutputStream

/** Запись big-endian для синтетических MPLS/CLPI. */
class BeWriter {
    private val out = ByteArrayOutputStream()
    val size get() = out.size()
    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
    fun u32(v: Long) = apply { u16((v ushr 16).toInt()); u16(v.toInt()) }
    fun u32(v: Int) = u32(v.toLong())
    fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun zeros(n: Int) = apply { repeat(n) { u8(0) } }
    fun toByteArray(): ByteArray = out.toByteArray()
}

data class StreamSpec(val pid: Int, val coding: Int, val lang: String = "und", val formatRate: Int = 0x61)

data class ItemSpec(
    val clip: String,
    val inTime: Long,
    val outTime: Long,
    val angles: Int = 1,
    val video: List<StreamSpec> = listOf(StreamSpec(0x1011, 0x1B, formatRate = 0x61)),
    val audio: List<StreamSpec> = listOf(StreamSpec(0x1100, 0x83, "eng")),
    val pg: List<StreamSpec> = emptyList(),
)

data class MarkSpec(val type: Int, val item: Int, val time: Long)

/** Синтетический MPLS по спецификации (как пишут реальные авторинговые программы). */
fun buildMpls(items: List<ItemSpec>, marks: List<MarkSpec>, version: String = "0200"): ByteArray {
    val playList = BeWriter().apply {
        val body = BeWriter().u16(0).u16(items.size).u16(0)
        items.forEach { body.bytes(buildItem(it)) }
        u32(body.size.toLong()).bytes(body.toByteArray())
    }.toByteArray()
    val markBytes = BeWriter().apply {
        val body = BeWriter().u16(marks.size)
        marks.forEach { body.u8(0).u8(it.type).u16(it.item).u32(it.time).u16(0xFFFF).u32(0) }
        u32(body.size.toLong()).bytes(body.toByteArray())
    }.toByteArray()
    val appInfo = BeWriter().u32(4).zeros(4).toByteArray()
    val playListStart = 40 + appInfo.size
    val marksStart = playListStart + playList.size
    return BeWriter().ascii("MPLS").ascii(version).u32(playListStart).u32(marksStart).u32(0).zeros(20)
        .bytes(appInfo).bytes(playList).bytes(markBytes).toByteArray()
}

private fun buildItem(s: ItemSpec): ByteArray {
    val w = BeWriter().ascii(s.clip).ascii("M2TS")
        .u16((if (s.angles > 1) 1 shl 4 else 0) or 1).u8(0).u32(s.inTime).u32(s.outTime)
        .zeros(8).u8(0).u8(0).u16(0)
    if (s.angles > 1) {
        w.u8(s.angles).u8(0)
        repeat(s.angles - 1) { w.ascii("%05d".format(90 + it)).ascii("M2TS").u8(0) }
    }
    val stn = BeWriter().u16(0).u8(s.video.size).u8(s.audio.size).u8(s.pg.size).u8(0).u8(0).u8(0).u8(0).zeros(5)
    fun entry(pid: Int) = stn.u8(9).u8(1).u16(pid).zeros(6)
    s.video.forEach { entry(it.pid); stn.u8(5).u8(it.coding).u8(it.formatRate).zeros(3) }
    s.audio.forEach { entry(it.pid); stn.u8(5).u8(it.coding).u8(it.formatRate).ascii(it.lang) }
    s.pg.forEach { entry(it.pid); stn.u8(5).u8(0x90).ascii(it.lang).u8(0) }
    w.u16(stn.size).bytes(stn.toByteArray())
    return BeWriter().u16(w.size).bytes(w.toByteArray()).toByteArray()
}

/**
 * Крошечный UDF-образ: корень → BDMV → PLAYLIST/00001.mpls, STREAM/00001.m2ts (фрагментирован,
 * с продолжением списка дескрипторов), и встроенный в ICB файл с именем в UTF-16.
 * [metadata] = true — UDF 2.50 с разделом метаданных, иначе UDF 1.02.
 */
class UdfImageBuilder(private val metadata: Boolean, private val mpls: ByteArray) {
    val image = ByteArray(TOTAL * BS)

    /** Ожидаемое содержимое m2ts: 2 полных блока в физ. блоках 100–101 + 1000 байт в блоке 80. */
    val m2ts: ByteArray = ByteArray(2 * BS + 1000) { (it * 7 + it / BS).toByte() }

    private val metaRef = if (metadata) 1 else 0

    private fun metaLbn(n: Int) = if (metadata) n else 10 + n

    /** Сектор образа, где лежит блок n пространства метаданных. */
    private fun metaSector(n: Int) = PART_START + if (!metadata) 10 + n else if (n < 6) 10 + n else 20 + (n - 6)

    fun build(): ByteArray {
        // Якорь и последовательность дескрипторов тома.
        tag(256 * BS, 2, 256).also {
            le32(256 * BS + 16, 16 * BS); le32(256 * BS + 20, 32)
            le32(256 * BS + 24, 16 * BS); le32(256 * BS + 28, 32)
            finishTag(256 * BS)
        }
        val pd = 32 * BS
        tag(pd, 5, 32); le16(pd + 22, 0); le32(pd + 188, PART_START); le32(pd + 192, PART_LEN); finishTag(pd)
        val lvd = 33 * BS
        tag(lvd, 6, 33)
        image[lvd + 84] = 8; "TEST".toByteArray().copyInto(image, lvd + 85); image[lvd + 84 + 127] = 5
        le32(lvd + 212, BS)
        le32(lvd + 248, BS); le32(lvd + 252, metaLbn(0)); le16(lvd + 256, metaRef)
        var p = lvd + 440
        image[p] = 1; image[p + 1] = 6; le16(p + 2, 1); le16(p + 4, 0); p += 6
        if (metadata) {
            image[p] = 2; image[p + 1] = 64
            "*UDF Metadata Partition".toByteArray().copyInto(image, p + 5)
            le16(p + 36, 1); le16(p + 38, 0); le32(p + 40, 0); le32(p + 44, 199); le32(p + 48, -1)
            le32(p + 52, 32); le16(p + 56, 1)
            p += 64
        }
        le32(lvd + 264, p - (lvd + 440)); le32(lvd + 268, if (metadata) 2 else 1)
        finishTag(lvd)
        tag(34 * BS, 8, 34); finishTag(34 * BS)

        if (metadata) {
            // Файл метаданных в физ. блоке 0: два экстента (блоки 10–15 и 20–26).
            fe(PART_START * BS, fileType = 250, size = 13L * BS, adFlag = 0,
                ads = shortAd(6 * BS, 10) + shortAd(7 * BS, 20))
        }

        // FSD → корень.
        val fsd = metaSector(0) * BS
        tag(fsd, 256, 0); longAd(fsd + 400, BS, metaLbn(1), metaRef); finishTag(fsd)

        dir(feBlock = 1, dataBlock = 2, listOf(
            Fid("BDMV", 3, dir = true),
            Fid("Ünïcødé файл.txt", 4, utf16 = true, iuLen = 2),
            Fid("deleted.txt", 4, deleted = true),
        ))
        fe(metaSector(4) * BS, fileType = 5, size = 11, adFlag = 3, ads = "hello world".toByteArray())

        dir(3, 5, listOf(Fid("PLAYLIST", 6, dir = true), Fid("STREAM", 7, dir = true)))
        dir(6, 8, listOf(Fid("00001.mpls", 9)))
        // mpls: Extended File Entry, short_ad на физические блоки 60+.
        mpls.copyInto(image, (PART_START + 60) * BS)
        fe(metaSector(9) * BS, fileType = 5, size = mpls.size.toLong(), adFlag = 0,
            ads = shortAd(mpls.size, 60), extended = true)

        dir(7, 10, listOf(Fid("00001.m2ts", 11)))
        // m2ts: long_ad (блоки 100–101), затем продолжение в AED (блок 12) с последним экстентом (блок 80).
        m2ts.copyInto(image, (PART_START + 100) * BS, 0, 2 * BS)
        m2ts.copyInto(image, (PART_START + 80) * BS, 2 * BS, m2ts.size)
        fe(metaSector(11) * BS, fileType = 5, size = m2ts.size.toLong(), adFlag = 1,
            ads = longAdBytes(2 * BS, 100, 0) + longAdBytes(BS or (3 shl 30), metaLbn(12), metaRef))
        val aed = metaSector(12) * BS
        tag(aed, 258, 12)
        val last = longAdBytes(1000, 80, 0)
        le32(aed + 20, last.size); last.copyInto(image, aed + 24)
        finishTag(aed)
        return image
    }

    class Fid(val name: String, val feBlock: Int, val dir: Boolean = false, val utf16: Boolean = false,
              val deleted: Boolean = false, val iuLen: Int = 0)

    /** Каталог: FE в блоке [feBlock] метапространства, FID'ы в блоке [dataBlock]. */
    private fun dir(feBlock: Int, dataBlock: Int, entries: List<Fid>) {
        val data = ByteArrayOutputStream()
        data.write(fid(null, feBlock, parent = true))
        entries.forEach { data.write(fid(it, it.feBlock)) }
        val bytes = data.toByteArray()
        bytes.copyInto(image, metaSector(dataBlock) * BS)
        fe(metaSector(feBlock) * BS, fileType = 4, size = bytes.size.toLong(), adFlag = 0,
            ads = shortAd(bytes.size, metaLbn(dataBlock)))
    }

    private fun fid(f: Fid?, target: Int, parent: Boolean = false): ByteArray {
        val name = when {
            f == null -> ByteArray(0)
            f.utf16 -> byteArrayOf(16) + f.name.toByteArray(Charsets.UTF_16BE)
            else -> byteArrayOf(8) + f.name.toByteArray(Charsets.ISO_8859_1)
        }
        val iu = f?.iuLen ?: 0
        val total = (38 + iu + name.size + 3) and 3.inv()
        val b = ByteArray(total)
        b[0] = 1; b[1] = 1 // тег 257
        b[2] = 2
        b[16] = 1
        var ch = 0
        if (f?.dir == true) ch = ch or 2
        if (f?.deleted == true) ch = ch or 4
        if (parent) ch = ch or 0x0A
        b[18] = ch.toByte()
        b[19] = name.size.toByte()
        longAdBytes(BS, metaLbn(target), metaRef).copyInto(b, 20)
        b[36] = iu.toByte()
        name.copyInto(b, 38 + iu)
        checksum(b, 0)
        return b
    }

    private fun fe(off: Int, fileType: Int, size: Long, adFlag: Int, ads: ByteArray, extended: Boolean = false) {
        tag(off, if (extended) 266 else 261, 0)
        image[off + 27] = fileType.toByte()
        le16(off + 34, adFlag)
        le32(off + 56, size.toInt())
        val adPos = if (extended) 216 else 176
        le32(off + adPos - 4, ads.size)
        ads.copyInto(image, off + adPos)
        finishTag(off)
    }

    private fun shortAd(len: Int, lbn: Int) = ByteArray(8).also { le32(it, 0, len); le32(it, 4, lbn) }

    private fun longAdBytes(len: Int, lbn: Int, ref: Int) =
        ByteArray(16).also { le32(it, 0, len); le32(it, 4, lbn); it[8] = ref.toByte() }

    private fun longAd(off: Int, len: Int, lbn: Int, ref: Int) = longAdBytes(len, lbn, ref).copyInto(image, off)

    private fun tag(off: Int, id: Int, location: Int) {
        le16(off, id); le16(off + 2, 2); le32(off + 12, location)
    }

    private fun finishTag(off: Int) = checksum(image, off)

    private fun checksum(b: ByteArray, off: Int) {
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += b[off + i].toInt() and 0xFF
        b[off + 4] = sum.toByte()
    }

    private fun le16(off: Int, v: Int) { image[off] = v.toByte(); image[off + 1] = (v ushr 8).toByte() }
    private fun le32(off: Int, v: Int) = le32(image, off, v)
    private fun le32(b: ByteArray, off: Int, v: Int) { for (i in 0 until 4) b[off + i] = (v ushr (8 * i)).toByte() }

    companion object {
        const val BS = 2048
        const val PART_START = 300
        const val PART_LEN = 200
        const val TOTAL = PART_START + PART_LEN
    }
}
