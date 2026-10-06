package tv.p2160.core.intro

import tv.p2160.core.api.SegmentType
import tv.p2160.core.api.SkipSegment
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Итог поиска для файла. Уверенность 0…1; для автопропуска разумно требовать ≥ 0.6. */
data class DetectionResult(
    val intro: SkipSegment? = null,
    val credits: SkipSegment? = null,
    val introConfidence: Float = 0f,
    val creditsConfidence: Float = 0f,
) {
    val segments: List<SkipSegment> get() = listOfNotNull(intro, credits)

    companion object {
        val EMPTY = DetectionResult()
    }
}

/** Отпечаток участка файла и длительность самого файла (для титров «до конца»). */
class StoredFingerprint(val fingerprint: Fingerprint, val fileDurationMs: Long)

/** Шаблоны сериала: отпечатки найденных ранее заставок и титров, новые первыми. */
class SeriesTemplates(val intros: List<Fingerprint>, val credits: List<Fingerprint>)

/**
 * Дисковый кэш: отпечатки по файлам, результаты по файлам, шаблоны по сериалам.
 * Ключи хэшируются в имена файлов; отпечатки сжаты (≈40 КБ на 10 минут).
 */
class IntroStore(private val dir: File, private val maxFingerprints: Int = 400) {

    private val fpDir = File(dir, "fp")
    private val resDir = File(dir, "res")
    private val seriesDir = File(dir, "series")

    @Synchronized
    fun fingerprint(key: String): StoredFingerprint? = runCatching {
        val f = File(fpDir, name(key) + ".bin")
        if (!f.exists()) return null
        DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
            val duration = input.readLong()
            StoredFingerprint(readFp(input), duration)
        }
    }.getOrNull()

    @Synchronized
    fun putFingerprint(key: String, value: StoredFingerprint) {
        runCatching {
            fpDir.mkdirs()
            write(File(fpDir, name(key) + ".bin")) { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeLong(value.fileDurationMs)
                writeFp(out, value.fingerprint)
            }
            prune()
        }
    }

    fun result(key: String): DetectionResult? = resultEntry(key)?.first

    /** Результат и хэши ключей соседей, с которыми он получен. */
    @Synchronized
    fun resultEntry(key: String): Pair<DetectionResult, Set<String>>? = runCatching {
        val f = File(resDir, name(key) + ".txt")
        if (!f.exists()) return null
        val p = Properties().apply { f.inputStream().use(::load) }
        if (p.getProperty("v") != VERSION.toString()) return null
        val segments = SkipSegment.parseList(p.getProperty("segments"))
        DetectionResult(
            intro = segments.firstOrNull { it.type == SegmentType.INTRO },
            credits = segments.firstOrNull { it.type == SegmentType.CREDITS },
            introConfidence = p.getProperty("ic")?.toFloatOrNull() ?: 0f,
            creditsConfidence = p.getProperty("cc")?.toFloatOrNull() ?: 0f,
        ) to p.getProperty("sibs").orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
    }.getOrNull()

    @Synchronized
    fun putResult(key: String, result: DetectionResult, siblings: Collection<String> = emptyList()) {
        runCatching {
            resDir.mkdirs()
            val p = Properties()
            p.setProperty("v", VERSION.toString())
            p.setProperty("sibs", siblings.joinToString(",") { name(it) })
            p.setProperty("segments", SkipSegment.formatList(result.segments))
            p.setProperty("ic", result.introConfidence.toString())
            p.setProperty("cc", result.creditsConfidence.toString())
            val f = File(resDir, name(key) + ".txt")
            val tmp = File(resDir, f.name + ".tmp")
            tmp.outputStream().use { p.store(it, null) }
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
    }

    @Synchronized
    fun forgetResult(key: String) {
        File(resDir, name(key) + ".txt").delete()
    }

    @Synchronized
    fun templates(seriesKey: String): SeriesTemplates = runCatching {
        val f = File(seriesDir, name(seriesKey) + ".bin")
        if (!f.exists()) return SeriesTemplates(emptyList(), emptyList())
        DataInputStream(InflaterInputStream(f.inputStream().buffered())).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return SeriesTemplates(emptyList(), emptyList())
            val intros = List(input.readInt()) { readFp(input) }
            val credits = List(input.readInt()) { readFp(input) }
            SeriesTemplates(intros, credits)
        }
    }.getOrDefault(SeriesTemplates(emptyList(), emptyList()))

    @Synchronized
    fun putTemplates(seriesKey: String, value: SeriesTemplates) {
        runCatching {
            seriesDir.mkdirs()
            write(File(seriesDir, name(seriesKey) + ".bin")) { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(value.intros.size)
                value.intros.forEach { writeFp(out, it) }
                out.writeInt(value.credits.size)
                value.credits.forEach { writeFp(out, it) }
            }
        }
    }

    @Synchronized
    fun clear() {
        dir.deleteRecursively()
    }

    private fun prune() {
        val files = fpDir.listFiles() ?: return
        if (files.size <= maxFingerprints) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFingerprints).forEach { it.delete() }
    }

    private inline fun write(target: File, body: (DataOutputStream) -> Unit) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        DataOutputStream(DeflaterOutputStream(tmp.outputStream().buffered())).use(body)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun writeFp(out: DataOutputStream, fp: Fingerprint) {
        out.writeLong(fp.startMs)
        out.writeDouble(fp.hopMs)
        out.writeDouble(fp.leadMs)
        out.writeInt(fp.size)
        for (v in fp.frames) out.writeInt(v)
        for (v in fp.masks) out.writeInt(v)
    }

    private fun readFp(input: DataInputStream): Fingerprint {
        val start = input.readLong()
        val hop = input.readDouble()
        val lead = input.readDouble()
        val n = input.readInt()
        require(n in 0..MAX_FRAMES)
        val frames = IntArray(n) { input.readInt() }
        val masks = IntArray(n) { input.readInt() }
        return Fingerprint(start, hop, frames, masks, lead)
    }

    fun name(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAGIC = 0x50324650 // «P2FP»
        /** Меняется вместе с алгоритмом отпечатков — старый кэш тогда игнорируется. */
        const val VERSION = 1
        const val MAX_FRAMES = 200_000
    }
}
