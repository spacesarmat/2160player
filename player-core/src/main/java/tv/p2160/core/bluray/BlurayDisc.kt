package tv.p2160.core.bluray

import java.io.Closeable

/** Отрезок клипа в составе плейлиста. Время клипа — в тиках 45 кГц (как PTS/2). */
data class BlurayClip(
    val clipName: String,
    /** Путь m2ts относительно корня файловой системы диска. */
    val m2tsPath: String,
    val inTime45k: Long,
    val outTime45k: Long,
    /** Начало отрезка на шкале плейлиста. */
    val playlistStart45k: Long,
    val connectionCondition: Int,
    val angleCount: Int,
) {
    val duration45k: Long get() = (outTime45k - inTime45k).coerceAtLeast(0)
    val durationMs: Long get() = ticksToMs(duration45k)
}

/** Позиция внутри плейлиста: элемент и время на шкале его клипа (45 кГц). */
data class ClipPosition(val itemIndex: Int, val clipTime45k: Long)

/** Плейлист диска как воспроизводимый «фильм». */
data class BlurayTitle(
    /** Имя без расширения: "00800". */
    val playlistName: String,
    val items: List<BlurayClip>,
    /** Начала глав в мс от начала плейлиста. */
    val chapters: List<Long>,
    val videoStreams: List<BlurayStream>,
    val audioStreams: List<BlurayStream>,
    val subtitleStreams: List<BlurayStream>,
) {
    val duration45k: Long = items.sumOf { it.duration45k }
    val durationMs: Long get() = ticksToMs(duration45k)

    /** Сколько раз клипы повторяются внутри плейлиста (признак плейлистов-обманок). */
    val repeatedClipCount: Int get() = items.size - items.map { it.clipName }.distinct().size

    /** Доля соседних пар клипов, идущих по возрастанию имён; 1.0 для одного клипа. */
    val ascendingRatio: Double
        get() = if (items.size < 2) 1.0
        else items.zipWithNext().count { (a, b) -> b.clipName > a.clipName }.toDouble() / (items.size - 1)

    /** Время плейлиста (мс) → элемент и время клипа. За пределами — края. */
    fun locate(playlistMs: Long): ClipPosition {
        if (items.isEmpty()) return ClipPosition(0, 0)
        val t = (playlistMs.coerceAtLeast(0) * 45).coerceAtMost(duration45k)
        val i = items.indexOfLast { it.playlistStart45k <= t }.coerceAtLeast(0)
        val item = items[i]
        val inItem = (t - item.playlistStart45k).coerceIn(0, item.duration45k)
        return ClipPosition(i, item.inTime45k + inItem)
    }

    /** Элемент и время его клипа (45 кГц) → время плейлиста в мс. */
    fun toPlaylistMs(itemIndex: Int, clipTime45k: Long): Long {
        val item = items.getOrNull(itemIndex) ?: return 0
        val inItem = (clipTime45k - item.inTime45k).coerceIn(0, item.duration45k)
        return ticksToMs(item.playlistStart45k + inItem)
    }

    /** Короткое описание для логов. */
    fun describe(): String = buildString {
        append("$playlistName.mpls  ${formatMs(durationMs)}  clips=${items.size}  chapters=${chapters.size}\n")
        items.forEach {
            append("  clip ${it.clipName}  in=${it.inTime45k} out=${it.outTime45k}  ${formatMs(it.durationMs)}  ${it.m2tsPath}\n")
        }
        (videoStreams + audioStreams + subtitleStreams).forEach {
            append("  %-8s pid=0x%04X %-4s %s\n".format(it.kind, it.pid, it.language ?: "-", it.description))
        }
        if (chapters.isNotEmpty()) append("  chapters: ${chapters.joinToString(" ") { formatMs(it) }}\n")
    }
}

/**
 * Структура диска Blu-ray: плейлисты, главный фильм, название.
 * Открывается поверх любой [DiscFileSystem] — ISO или папки (корень диска либо сама BDMV).
 */
class BlurayDisc private constructor(
    val fs: DiscFileSystem,
    /** Путь каталога BDMV в [fs] ("BDMV" или "" если корень — сама BDMV). */
    val bdmvPath: String,
    val titles: List<BlurayTitle>,
    /** Название из BDMV/META/DL/bdmt_*.xml. */
    val discTitle: String?,
) : Closeable {

    private val clipInfoCache = HashMap<String, ClipInfo?>()

    /**
     * Главный фильм:
     * 1. Плейлисты короче 10 минут не рассматриваются (если есть хоть один длиннее).
     * 2. Кандидаты — плейлисты в пределах 1% от самого длинного. На дисках с защитой от
     *    копирования (Disney, Lionsgate) их десятки с одинаковой длиной и перемешанными клипами.
     * 3. Среди кандидатов: меньше повторов клипов → больше доля клипов по возрастанию →
     *    больше глав → длиннее → меньший номер плейлиста.
     */
    fun mainTitle(): BlurayTitle? {
        if (titles.isEmpty()) return null
        val long = titles.filter { it.durationMs >= MIN_MAIN_MS }.ifEmpty { titles }
        val max = long.maxOf { it.duration45k }
        val group = long.filter { it.duration45k >= max - maxOf(max / 100, 45_000) }
        return group.sortedWith(
            compareBy<BlurayTitle> { it.repeatedClipCount }
                .thenByDescending { it.ascendingRatio }
                .thenByDescending { it.chapters.size }
                .thenByDescending { it.duration45k }
                .thenBy { it.playlistName },
        ).first()
    }

    /** Много плейлистов одинаковой длины — похоже на защиту плейлистами-обманками. */
    val looksObfuscated: Boolean
        get() {
            val main = mainTitle() ?: return false
            return titles.count { kotlin.math.abs(it.duration45k - main.duration45k) <= main.duration45k / 100 } > 5
        }

    fun title(playlistName: String): BlurayTitle? =
        titles.firstOrNull { it.playlistName.equals(playlistName.removeSuffix(".mpls"), ignoreCase = true) }

    /** CLPI клипа (потоки, EP_map); null — нет файла или он повреждён. */
    fun clipInfo(clipName: String): ClipInfo? = synchronized(clipInfoCache) {
        clipInfoCache.getOrPut(clipName) {
            runCatching { ClpiParser.parse(fs.readAll(join(bdmvPath, "CLIPINF/$clipName.clpi"))) }.getOrNull()
        }
    }

    override fun close() = fs.close()

    companion object {
        const val MIN_MAIN_MS = 10 * 60 * 1000L

        /** Читает структуру диска. Повреждённые плейлисты пропускаются. */
        fun open(fs: DiscFileSystem): BlurayDisc {
            val bdmv = when {
                fs.stat("BDMV")?.isDirectory == true -> "BDMV"
                fs.stat("PLAYLIST")?.isDirectory == true -> ""
                else -> throw BlurayFormatException("BDMV directory not found")
            }
            val playlists = fs.list(join(bdmv, "PLAYLIST")).orEmpty()
                .filter { !it.isDirectory && it.name.endsWith(".mpls", ignoreCase = true) }
                .sortedBy { it.name.uppercase() }
            val titles = playlists.mapNotNull { entry ->
                runCatching {
                    val mpls = MplsParser.parse(fs.readAll(join(bdmv, "PLAYLIST/${entry.name}")))
                    buildTitle(entry.name.substringBeforeLast('.'), mpls, bdmv)
                }.getOrNull()
            }.filter { it.items.isNotEmpty() }
            return BlurayDisc(fs, bdmv, titles, readDiscTitle(fs, bdmv))
        }

        internal fun buildTitle(name: String, mpls: MplsPlaylist, bdmv: String): BlurayTitle {
            var start = 0L
            val clips = mpls.items.map { item ->
                BlurayClip(
                    clipName = item.clipName,
                    m2tsPath = join(bdmv, "STREAM/${item.clipName}.m2ts"),
                    inTime45k = item.inTime45k,
                    outTime45k = item.outTime45k,
                    playlistStart45k = start,
                    connectionCondition = item.connectionCondition,
                    angleCount = item.angleCount,
                ).also { start += it.duration45k }
            }
            val chapters = mpls.marks
                .filter { it.type == MplsParser.MARK_ENTRY && it.playItemRef in clips.indices }
                .map { m ->
                    val clip = clips[m.playItemRef]
                    ticksToMs(clip.playlistStart45k + (m.time45k - clip.inTime45k).coerceIn(0, clip.duration45k))
                }
                .distinct().sorted()
                // Метка в самом конце (часто бывает) — не глава.
                .filter { it == 0L || it < ticksToMs(start) - 1000 }
            val first = mpls.items.firstOrNull()
            return BlurayTitle(
                playlistName = name,
                items = clips,
                chapters = chapters,
                videoStreams = first?.videoStreams.orEmpty(),
                audioStreams = first?.audioStreams.orEmpty(),
                subtitleStreams = first?.pgStreams.orEmpty(),
            )
        }

        /** `<di:name>` из bdmt_eng.xml, иначе из любого bdmt_*.xml. */
        private fun readDiscTitle(fs: DiscFileSystem, bdmv: String): String? {
            val dir = join(bdmv, "META/DL")
            val files = fs.list(dir).orEmpty()
                .filter { !it.isDirectory && it.name.startsWith("bdmt_", ignoreCase = true) && it.name.endsWith(".xml", ignoreCase = true) }
                .sortedBy { if (it.name.equals("bdmt_eng.xml", ignoreCase = true)) 0 else 1 }
            for (f in files) {
                val xml = runCatching { String(fs.readAll("$dir/${f.name}", 1024 * 1024), Charsets.UTF_8) }.getOrNull() ?: continue
                val name = Regex("<di:name>([^<]*)</di:name>").find(xml)?.groupValues?.get(1)?.let(::unescapeXml)?.trim()
                if (!name.isNullOrEmpty()) return name
            }
            return null
        }

        private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"
    }
}

internal fun ticksToMs(ticks45k: Long): Long = ticks45k / 45

internal fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
}
