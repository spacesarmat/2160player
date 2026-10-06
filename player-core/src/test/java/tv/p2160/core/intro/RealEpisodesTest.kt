package tv.p2160.core.intro

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tv.p2160.core.api.SegmentType
import tv.p2160.core.engine.SegmentDetector
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Проверка на настоящих сериях (не запускается без данных).
 *
 * P2160_INTRO_PCM — папка кэша PCM: <сериал>/SxxEyy.head.s16 (первые 600 с), .tail.s16 (последние 360 с), .info.txt (вывод `ffmpeg -i`).
 * P2160_INTRO_SEASONS — папки сезонов через «;»: первые три серии будут декодированы в кэш с помощью
 * P2160_FFMPEG (`-ac 1 -ar 11025 -f s16le`). Исходные файлы только читаются.
 */
class RealEpisodesTest {

    private val rate = 11_025
    private val fp = Fingerprinter()

    @Test
    fun realEpisodes() {
        val cache = File(System.getenv("P2160_INTRO_PCM") ?: File(System.getProperty("java.io.tmpdir"), "p2160-intro-pcm").path)
        val ffmpeg = System.getenv("P2160_FFMPEG")?.let(::File)?.takeIf { it.exists() }
        val seasons = System.getenv("P2160_INTRO_SEASONS").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }
        if (ffmpeg != null) seasons.forEach { decodeSeason(ffmpeg, File(it), cache) }

        val series = cache.listFiles().orEmpty().filter { d -> d.isDirectory && (d.listFiles()?.count { it.name.endsWith(".head.s16") } ?: 0) >= 2 }
        assumeTrue("no real episodes in $cache", series.isNotEmpty())

        val failures = ArrayList<String>()
        for (dir in series.sortedBy { it.name }) {
            val tags = dir.listFiles()!!.filter { it.name.endsWith(".head.s16") }.map { it.name.removeSuffix(".head.s16") }.sorted()
            val heads = HashMap<String, Fingerprint>()
            val tails = HashMap<String, Fingerprint>()
            val infos = tags.associateWith { Info.parse(File(dir, "$it.info.txt")) }
            for (tag in tags) {
                val t0 = System.nanoTime()
                heads[tag] = fp.fingerprint(readPcm(File(dir, "$tag.head.s16")))
                val ms = (System.nanoTime() - t0) / 1_000_000
                val tailFile = File(dir, "$tag.tail.s16")
                val dur = infos[tag]?.durationMs ?: -1
                if (tailFile.exists() && dur > 0) {
                    val pcm = readPcm(tailFile)
                    tails[tag] = fp.fingerprint(pcm, startMs = dur - pcm.size * 1000L / rate)
                }
                println("${dir.name}/$tag: fingerprint 600 s in $ms ms")
            }
            for ((i, tag) in tags.withIndex()) {
                val others = tags.filterIndexed { j, _ -> j != i }.sortedBy { abs(tags.indexOf(it) - i) }.take(2)
                val t0 = System.nanoTime()
                val intro = IntroMatcher.findIntro(heads[tag]!!, others.map { heads[it]!! })
                val credits = tails[tag]?.let { cur ->
                    IntroMatcher.findCredits(cur, others.mapNotNull { tails[it] })
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val info = infos[tag]
                println(
                    "${dir.name}/$tag vs $others (${ms} ms): intro=${intro?.fmt()} credits=${credits?.fmt()}" +
                        " | chapters intro=${info?.intro?.fmtPair()} credits=${info?.credits?.fmtPair()} dur=${info?.durationMs?.div(1000.0)}",
                )
                info?.intro?.let { (s, e) ->
                    if (intro == null || abs(intro.startMs - s) > 3_000 || abs(intro.endMs - e) > 3_000) {
                        failures += "${dir.name}/$tag intro ${intro?.fmt()} vs chapter ${s / 1000.0}-${e / 1000.0}"
                    }
                }
                info?.credits?.let { (s, _) ->
                    if (credits == null || abs(credits.startMs - s) > 3_000) {
                        failures += "${dir.name}/$tag credits ${credits?.fmt()} vs chapter ${s / 1000.0}"
                    }
                }
            }
        }
        println(failures.joinToString("\n", prefix = "mismatches vs chapters:\n"))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun IntroMatcher.Found.fmt() = "%.1f-%.1f (conf %.2f, bits %.1f)".format(startMs / 1000.0, endMs / 1000.0, confidence, match.meanBits)
    private fun Pair<Long, Long>.fmtPair() = "%.1f-%.1f".format(first / 1000.0, second / 1000.0)

    private fun readPcm(f: File): ShortArray {
        val bytes = f.readBytes()
        return ShortArray(bytes.size / 2) { ((bytes[2 * it].toInt() and 0xff) or (bytes[2 * it + 1].toInt() shl 8)).toShort() }
    }

    /** Длительность и главы из вывода `ffmpeg -i`. */
    private class Info(val durationMs: Long, val intro: Pair<Long, Long>?, val credits: Pair<Long, Long>?) {
        companion object {
            private val duration = Regex("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)")
            private val chapter = Regex("Chapter #\\d+:\\d+: start ([\\d.]+), end ([\\d.]+)")
            private val title = Regex("^\\s*title\\s*: (.*)$")

            fun parse(f: File): Info? {
                if (!f.exists()) return null
                val lines = f.readLines()
                val d = lines.firstNotNullOfOrNull { duration.find(it) } ?: return null
                val dur = ((d.groupValues[1].toLong() * 3600 + d.groupValues[2].toLong() * 60) * 1000 + (d.groupValues[3].toDouble() * 1000).toLong())
                var intro: Pair<Long, Long>? = null
                var credits: Pair<Long, Long>? = null
                for ((i, line) in lines.withIndex()) {
                    val m = chapter.find(line) ?: continue
                    val name = lines.getOrNull(i + 1)?.let { title.find(it)?.groupValues?.get(1) }
                    val range = (m.groupValues[1].toDouble() * 1000).toLong() to (m.groupValues[2].toDouble() * 1000).toLong()
                    when (SegmentDetector.typeOfChapter(name)) {
                        SegmentType.INTRO -> if (intro == null) intro = range
                        SegmentType.CREDITS -> credits = range
                        else -> Unit
                    }
                }
                return Info(dur, intro, credits)
            }
        }
    }

    /** Декодирует первые три серии сезона в кэш (если ещё нет). */
    private fun decodeSeason(ffmpeg: File, season: File, cache: File) {
        val videos = season.listFiles().orEmpty().filter { it.extension.lowercase() in setOf("mkv", "mp4", "avi", "ts", "m2ts") }.sortedBy { it.name }.take(3)
        val out = File(cache, season.parentFile?.name.orEmpty().filter { it.isLetterOrDigit() }).apply { mkdirs() }
        for (v in videos) {
            val tag = Regex("(?i)S\\d+E\\d+").find(v.name)?.value?.uppercase() ?: v.nameWithoutExtension.take(20)
            val info = File(out, "$tag.info.txt")
            if (!info.exists()) {
                val p = ProcessBuilder(ffmpeg.path, "-hide_banner", "-i", v.path).redirectErrorStream(true).start()
                info.writeText(p.inputStream.bufferedReader().readText())
                p.waitFor(1, TimeUnit.MINUTES)
            }
            for ((suffix, args) in listOf("head" to listOf("-t", "600", "-i", v.path), "tail" to listOf("-sseof", "-360", "-i", v.path))) {
                val f = File(out, "$tag.$suffix.s16")
                if (f.length() > 0) continue
                val cmd = listOf(ffmpeg.path, "-v", "error", "-y") + args + listOf("-map", "0:a:0", "-ac", "1", "-ar", "$rate", "-f", "s16le", f.path)
                ProcessBuilder(cmd).inheritIO().start().waitFor(30, TimeUnit.MINUTES)
            }
        }
    }
}
