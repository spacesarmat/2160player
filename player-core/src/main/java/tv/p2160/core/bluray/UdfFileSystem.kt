package tv.p2160.core.bluray

import java.io.FileNotFoundException
import java.io.IOException

/**
 * Чтение UDF-образа (ISO Blu-ray): UDF 1.02–2.60, включая метаданные в «*UDF Metadata Partition».
 * Только чтение и только то, что нужно для BD-ROM: без VAT, без таблиц переназначения секторов.
 *
 * Файл отдаётся как список экстентов поверх [source] — без копирования данных.
 * Закрывает [source] при закрытии, если [closeSource].
 */
class UdfFileSystem(
    private val source: RandomAccessSource,
    private val closeSource: Boolean = true,
) : DiscFileSystem {

    private sealed class PartitionMap(val partitionNumber: Int) {
        class Physical(number: Int) : PartitionMap(number)
        class Metadata(number: Int, val fileLocation: Long, val mirrorLocation: Long) : PartitionMap(number)
    }

    /** Адрес в логическом томе: номер карты разделов + номер логического блока. */
    private data class LbAddr(val partRef: Int, val lbn: Long)

    /** Дескриптор размещения: тип 0 — записан, 1/2 — не записан (читается нулями). */
    private class Ad(val partRef: Int, val lbn: Long, val length: Long, val type: Int)

    private class Node(
        val isDirectory: Boolean,
        val size: Long,
        val ads: List<Ad>,
        val embedded: ByteArray?,
    )

    private class Child(val entry: DiscEntry, val icb: LbAddr)

    private val partitionStarts = HashMap<Int, Long>() // номер раздела → первый сектор
    private val maps = ArrayList<PartitionMap>()
    private var blockSize = SECTOR
    private var metadataExtents: List<DiscExtent> = emptyList()
    private val root: Node
    private val nodeCache = HashMap<LbAddr, Node>()
    private val dirCache = HashMap<LbAddr, List<Child>>()

    // Корень кэшируем по фиктивному адресу.
    private val rootIcb = LbAddr(-1, -1)

    /** Идентификатор тома из Logical Volume Descriptor. */
    var volumeId: String = ""
        private set

    init {
        val avdp = readAnchor() ?: throw IOException("UDF anchor not found")
        val mainLen = u32le(avdp, 16)
        val mainLoc = u32le(avdp, 20)
        val reserveLen = u32le(avdp, 24)
        val reserveLoc = u32le(avdp, 28)
        val fsd = readVolumeDescriptors(mainLoc, mainLen)
            ?: readVolumeDescriptors(reserveLoc, reserveLen)
            ?: throw IOException("UDF volume descriptor sequence is invalid")
        loadMetadataFile()
        val fsdBlock = readBlock(fsd)
        if (tagId(fsdBlock) != TAG_FSD) throw IOException("UDF file set descriptor not found")
        root = readNode(longAd(fsdBlock, 400) ?: throw IOException("Bad root ICB"))
        if (!root.isDirectory) throw IOException("UDF root is not a directory")
    }

    // region Тома и разделы

    private fun readAnchor(): ByteArray? {
        val last = source.size / SECTOR - 1
        for (sector in listOf(256L, last, last - 256)) {
            if (sector < 0 || sector > last) continue
            val b = runCatching { source.readBytes(sector * SECTOR, SECTOR) }.getOrNull() ?: continue
            if (tagId(b) == TAG_AVDP) return b
        }
        return null
    }

    /** Разбирает последовательность дескрипторов тома; возвращает адрес File Set Descriptor. */
    private fun readVolumeDescriptors(location: Long, length: Long): LbAddr? {
        val count = minOf(length / SECTOR, MAX_VDS_SECTORS)
        var lvd: ByteArray? = null
        val pds = HashMap<Int, Long>()
        for (i in 0 until count) {
            val b = runCatching { source.readBytes((location + i) * SECTOR, SECTOR) }.getOrNull() ?: break
            when (tagId(b)) {
                TAG_PD -> pds.putIfAbsent(u16le(b, 22), u32le(b, 188))
                TAG_LVD -> if (lvd == null) lvd = b
                TAG_TD -> break
                -1 -> break
            }
        }
        val l = lvd ?: return null
        if (pds.isEmpty()) return null
        val bs = u32le(l, 212).toInt()
        if (bs < 512 || bs > 65536 || bs % 512 != 0) return null
        val parsedMaps = parsePartitionMaps(l) ?: return null
        val fsdRef = u16le(l, 248 + 8)
        if (fsdRef !in parsedMaps.indices) return null

        blockSize = bs
        partitionStarts.clear(); partitionStarts.putAll(pds)
        maps.clear(); maps.addAll(parsedMaps)
        volumeId = dstring(l, 84, 128)
        return LbAddr(fsdRef, u32le(l, 248 + 4))
    }

    private fun parsePartitionMaps(lvd: ByteArray): List<PartitionMap>? {
        val tableLen = u32le(lvd, 264).toInt()
        val count = u32le(lvd, 268).toInt()
        if (count !in 1..16 || tableLen < 0 || 440 + tableLen > lvd.size) return null
        val result = ArrayList<PartitionMap>()
        var p = 440
        repeat(count) {
            if (p + 2 > 440 + tableLen) return null
            val type = u8(lvd, p)
            val len = u8(lvd, p + 1)
            if (len < 6 || p + len > 440 + tableLen) return null
            result += when (type) {
                1 -> PartitionMap.Physical(u16le(lvd, p + 4))
                2 -> {
                    if (len < 64) return null
                    val ident = String(lvd, p + 5, 23, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')
                    val number = u16le(lvd, p + 38)
                    if (ident == "*UDF Metadata Partition") {
                        PartitionMap.Metadata(number, u32le(lvd, p + 40), u32le(lvd, p + 44))
                    } else {
                        // Sparable/virtual: на BD-ROM не встречаются, читаем как физический раздел.
                        PartitionMap.Physical(number)
                    }
                }
                else -> return null
            }
            p += len
        }
        return result
    }

    /** Загружает экстенты файла метаданных (основного или зеркала). */
    private fun loadMetadataFile() {
        val meta = maps.indexOfFirst { it is PartitionMap.Metadata }
        if (meta < 0) return
        val m = maps[meta] as PartitionMap.Metadata
        // Файл метаданных лежит в физическом разделе с тем же номером.
        val physRef = maps.indexOfFirst { it is PartitionMap.Physical && it.partitionNumber == m.partitionNumber }
        if (physRef < 0) throw IOException("No physical partition for UDF metadata")
        for (loc in listOf(m.fileLocation, m.mirrorLocation)) {
            val node = runCatching { readNode(LbAddr(physRef, loc), cache = false) }.getOrNull() ?: continue
            val ext = node.ads.flatMap { mapRange(it.partRef, it.lbn, it.length) }
            if (ext.isNotEmpty() && ext.all { it.position >= 0 }) {
                metadataExtents = ext
                return
            }
        }
        throw IOException("UDF metadata file is unreadable")
    }

    /** Переводит участок раздела в абсолютные экстенты образа. */
    private fun mapRange(partRef: Int, lbn: Long, length: Long): List<DiscExtent> {
        val map = maps.getOrNull(partRef) ?: throw IOException("Bad partition reference $partRef")
        return when (map) {
            is PartitionMap.Physical -> {
                val start = partitionStarts[map.partitionNumber] ?: throw IOException("No partition ${map.partitionNumber}")
                listOf(DiscExtent((start + lbn) * blockSize, length))
            }
            is PartitionMap.Metadata -> sliceMetadata(lbn * blockSize, length)
        }
    }

    /** Участок файла метаданных → экстенты образа. */
    private fun sliceMetadata(offset: Long, length: Long): List<DiscExtent> {
        val result = ArrayList<DiscExtent>()
        var pos = 0L
        var want = offset
        var left = length
        for (e in metadataExtents) {
            if (left <= 0) break
            if (want < pos + e.length) {
                val inExt = want - pos
                val n = minOf(left, e.length - inExt)
                result += DiscExtent(e.position + inExt, n)
                want += n
                left -= n
            }
            pos += e.length
        }
        if (left > 0) throw IOException("Address outside UDF metadata file")
        return result
    }

    private fun readBlock(addr: LbAddr): ByteArray {
        val buf = ByteArray(blockSize)
        var off = 0
        for (e in mapRange(addr.partRef, addr.lbn, blockSize.toLong())) {
            source.readFully(e.position, buf, off, e.length.toInt())
            off += e.length.toInt()
        }
        return buf
    }

    // endregion

    // region File Entry и каталоги

    private fun readNode(icb: LbAddr, cache: Boolean = true): Node {
        if (cache) synchronized(this) { nodeCache[icb]?.let { return it } }
        val b = readBlock(icb)
        val (adOffset, adLength) = when (tagId(b)) {
            TAG_FE -> 176 + u32le(b, 168).toInt() to u32le(b, 172).toInt()
            TAG_EFE -> 216 + u32le(b, 208).toInt() to u32le(b, 212).toInt()
            else -> throw IOException("Not a file entry at $icb")
        }
        if (adOffset < 0 || adLength < 0 || adOffset.toLong() + adLength > b.size) throw IOException("Bad file entry at $icb")
        val fileType = u8(b, 27)
        val flags = u16le(b, 34) and 7
        val size = u64le(b, 56)
        if (size < 0) throw IOException("Bad file size at $icb")
        val isDir = fileType == FILE_TYPE_DIRECTORY
        val node = if (flags == AD_EMBEDDED) {
            Node(isDir, minOf(size, adLength.toLong()), emptyList(), b.copyOfRange(adOffset, adOffset + adLength))
        } else {
            Node(isDir, size, readAds(b, adOffset, adLength, flags, icb.partRef), null)
        }
        if (cache) synchronized(this) { nodeCache[icb] = node }
        return node
    }

    /** Разбирает дескрипторы размещения, следуя по продолжениям (тип 3). */
    private fun readAds(first: ByteArray, firstOffset: Int, firstLength: Int, kind: Int, icbPart: Int): List<Ad> {
        val step = when (kind) {
            AD_SHORT -> 8
            AD_LONG -> 16
            AD_EXTENDED -> 20
            else -> throw IOException("Unknown allocation descriptor type $kind")
        }
        val result = ArrayList<Ad>()
        var buf = first
        var pos = firstOffset
        var end = firstOffset + firstLength
        var hops = 0
        while (pos + step <= end) {
            val raw = u32le(buf, pos)
            val type = (raw ushr 30).toInt()
            val len = raw and 0x3FFFFFFF
            val ad = when (kind) {
                AD_SHORT -> Ad(icbPart, u32le(buf, pos + 4), len, type)
                AD_LONG -> Ad(u16le(buf, pos + 8), u32le(buf, pos + 4), len, type)
                else -> Ad(u16le(buf, pos + 16), u32le(buf, pos + 12), len, type)
            }
            pos += step
            if (len == 0L) break // конец списка
            if (type == 3) {
                // Продолжение — Allocation Extent Descriptor (тег 258).
                if (++hops > MAX_AD_HOPS) throw IOException("Too many allocation extents")
                buf = readBlock(LbAddr(ad.partRef, ad.lbn))
                if (tagId(buf) != TAG_AED) throw IOException("Bad allocation extent")
                pos = 24
                end = minOf(buf.size, 24 + u32le(buf, 20).toInt().coerceAtLeast(0))
                continue
            }
            result += ad
            if (result.size > MAX_ADS) throw IOException("Too many allocation descriptors")
        }
        return result
    }

    /**
     * Экстенты содержимого узла. Данные файлов никогда не лежат в разделе метаданных:
     * short_ad из File Entry в метаразделе указывают на физический раздел с тем же номером.
     */
    private fun nodeExtents(node: Node): List<DiscExtent> {
        val result = ArrayList<DiscExtent>()
        var left = node.size
        for (ad in node.ads) {
            if (left <= 0) break
            val len = minOf(ad.length, left)
            left -= len
            if (ad.type != 0) {
                result += DiscExtent(-1, len)
                continue
            }
            var ref = ad.partRef
            val map = maps.getOrNull(ref)
            if (!node.isDirectory && map is PartitionMap.Metadata) {
                ref = maps.indexOfFirst { it is PartitionMap.Physical && it.partitionNumber == map.partitionNumber }
            }
            result += mapRange(ref, ad.lbn, len)
        }
        if (left > 0) result += DiscExtent(-1, left) // хвост без дескрипторов — нули
        return result
    }

    private fun openNode(node: Node): RandomAccessSource =
        node.embedded?.let { ByteArraySource(it) } ?: ExtentRandomAccessSource(source, nodeExtents(node))

    private fun children(icb: LbAddr, node: Node): List<Child> {
        synchronized(this) { dirCache[icb]?.let { return it } }
        if (node.size > MAX_DIR_SIZE) throw IOException("Directory too large")
        val data = openNode(node).use { it.readBytes(0, node.size.toInt()) }
        val result = ArrayList<Child>()
        var p = 0
        while (p + 38 <= data.size) {
            if (tagId(data, p) != TAG_FID) break
            val characteristics = u8(data, p + 18)
            val nameLen = u8(data, p + 19)
            val childIcb = longAd(data, p + 20)
            val iuLen = u16le(data, p + 36)
            val total = 38 + iuLen + nameLen
            if (p + total > data.size) break
            val isDeleted = characteristics and 0x04 != 0
            val isParent = characteristics and 0x08 != 0
            if (!isDeleted && !isParent && nameLen > 0 && childIcb != null) {
                val name = decodeCs0(data, p + 38 + iuLen, nameLen)
                val child = runCatching { readNode(childIcb) }.getOrNull()
                if (name.isNotEmpty() && child != null) {
                    result += Child(DiscEntry(name, child.isDirectory, if (child.isDirectory) 0 else child.size), childIcb)
                }
            }
            p += (total + 3) and 3.inv() // выравнивание на 4 байта
        }
        synchronized(this) { dirCache[icb] = result }
        return result
    }

    private fun resolve(path: String): Triple<LbAddr?, Node, DiscEntry>? {
        var icb: LbAddr? = null
        var node = root
        var entry = DiscEntry("", true, 0)
        for (part in splitPath(path)) {
            if (!node.isDirectory) return null
            val kids = children(icb ?: rootIcb, node)
            val child = kids.firstOrNull { it.entry.name == part }
                ?: kids.firstOrNull { it.entry.name.equals(part, ignoreCase = true) } ?: return null
            icb = child.icb
            node = readNode(child.icb)
            entry = child.entry
        }
        return Triple(icb, node, entry)
    }

    // endregion

    override fun list(path: String): List<DiscEntry>? {
        val (icb, node, _) = resolve(path) ?: return null
        if (!node.isDirectory) return null
        return children(icb ?: rootIcb, node).map { it.entry }
    }

    override fun stat(path: String): DiscEntry? = resolve(path)?.third

    override fun open(path: String): RandomAccessSource {
        val (_, node, _) = resolve(path) ?: throw FileNotFoundException(path)
        if (node.isDirectory) throw FileNotFoundException("$path is a directory")
        return openNode(node)
    }

    override fun extents(path: String): List<DiscExtent>? {
        val (_, node, _) = resolve(path) ?: return null
        if (node.isDirectory || node.embedded != null) return null
        return nodeExtents(node)
    }

    override fun close() {
        if (closeSource) source.close()
    }

    // region Байты

    private fun longAd(b: ByteArray, o: Int): LbAddr? {
        if (o + 16 > b.size) return null
        return LbAddr(u16le(b, o + 8), u32le(b, o + 4))
    }

    /** Идентификатор тега с проверкой контрольной суммы; -1 — не тег. */
    private fun tagId(b: ByteArray, o: Int = 0): Int {
        if (o + 16 > b.size) return -1
        var sum = 0
        for (i in 0 until 16) if (i != 4) sum += b[o + i].toInt() and 0xFF
        if ((sum and 0xFF) != u8(b, o + 4)) return -1
        return u16le(b, o)
    }

    /** dstring: последний байт — длина записанной части. */
    private fun dstring(b: ByteArray, o: Int, size: Int): String {
        val len = u8(b, o + size - 1)
        return if (len in 1 until size) decodeCs0(b, o, len) else ""
    }

    // endregion

    companion object {
        const val SECTOR = 2048

        private const val TAG_AVDP = 2
        private const val TAG_PD = 5
        private const val TAG_LVD = 6
        private const val TAG_TD = 8
        private const val TAG_FSD = 256
        private const val TAG_FID = 257
        private const val TAG_AED = 258
        private const val TAG_FE = 261
        private const val TAG_EFE = 266

        private const val FILE_TYPE_DIRECTORY = 4
        private const val AD_SHORT = 0
        private const val AD_LONG = 1
        private const val AD_EXTENDED = 2
        private const val AD_EMBEDDED = 3

        private const val MAX_VDS_SECTORS = 64L
        private const val MAX_AD_HOPS = 1024
        private const val MAX_ADS = 100_000
        private const val MAX_DIR_SIZE = 16L * 1024 * 1024

        /** Имя в OSTA CS0: 8 — по байту на символ, 16 — UTF-16BE. */
        internal fun decodeCs0(b: ByteArray, o: Int, len: Int): String {
            if (len < 1 || o + len > b.size) return ""
            return when (u8(b, o)) {
                8, 254 -> String(b, o + 1, len - 1, Charsets.ISO_8859_1)
                16, 255 -> String(b, o + 1, (len - 1) and 1.inv(), Charsets.UTF_16BE)
                else -> ""
            }.trimEnd('\u0000')
        }

        private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF
        private fun u16le(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)
        private fun u32le(b: ByteArray, o: Int): Long = (u16le(b, o).toLong()) or (u16le(b, o + 2).toLong() shl 16)
        private fun u64le(b: ByteArray, o: Int): Long = u32le(b, o) or (u32le(b, o + 4) shl 32)
    }
}
