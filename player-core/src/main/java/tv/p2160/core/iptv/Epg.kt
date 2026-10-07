package tv.p2160.core.iptv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.zip.GZIPInputStream

/** Передача из телепрограммы. */
data class EpgProgramme(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String? = null,
) {
    fun isOn(nowMs: Long): Boolean = nowMs in startMs until stopMs

    /** Доля прошедшего времени 0..1. */
    fun progress(nowMs: Long): Float =
        if (stopMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (stopMs - startMs)).coerceIn(0f, 1f)
}

/** Текущая и следующая передачи. */
data class NowNext(val now: EpgProgramme?, val next: EpgProgramme?)

/**
 * Телепрограмма в памяти: передачи по id канала XMLTV (отсортированы по времени)
 * и отображаемые имена каналов — для сопоставления по названию.
 */
class EpgData(
    val programmes: Map<String, List<EpgProgramme>>,
    val channelNames: Map<String, List<String>> = emptyMap(),
) {
    val isEmpty: Boolean get() = programmes.isEmpty()

    /** Объединение нескольких источников: при совпадении id побеждает первый, у кого есть передачи. */
    operator fun plus(other: EpgData): EpgData {
        val progs = LinkedHashMap(programmes)
        other.programmes.forEach { (id, list) -> if (progs[id].isNullOrEmpty()) progs[id] = list }
        val names = LinkedHashMap(channelNames)
        other.channelNames.forEach { (id, list) -> names.merge(id, list) { a, b -> (a + b).distinct() } }
        return EpgData(progs, names)
    }

    /** Убирает закончившиеся до [fromMs] передачи. */
    fun trimmed(fromMs: Long): EpgData =
        EpgData(programmes.mapValues { (_, l) -> l.filter { it.stopMs > fromMs } }.filterValues { it.isNotEmpty() }, channelNames)

    companion object {
        val EMPTY = EpgData(emptyMap())
    }
}

/**
 * Поиск передач для канала плейлиста: сначала по `tvg-id` (точно, затем без суффикса
 * `@HD`/`@SD` как у iptv-org), потом по нормализованному названию.
 */
class EpgGuide(val data: EpgData) {

    private val byId = HashMap<String, String>()
    private val byBaseId = HashMap<String, String>()
    private val byName = HashMap<String, String>()

    init {
        data.programmes.keys.forEach { id ->
            byId.putIfAbsent(id.lowercase(), id)
            byBaseId.putIfAbsent(baseId(id), id)
            byName.putIfAbsent(normalizeName(id), id)
        }
        // Имена из <display-name> важнее, чем совпадение id с названием.
        data.channelNames.forEach { (id, names) ->
            if (data.programmes.containsKey(id)) names.forEach { n -> normalizeName(n).takeIf { it.isNotEmpty() }?.let { byName[it] = id } }
        }
    }

    /** Ключ канала в EPG или null. */
    fun keyFor(channel: IptvChannel): String? {
        channel.tvgId?.let { tvg ->
            byId[tvg.lowercase()]?.let { return it }
            byBaseId[baseId(tvg)]?.let { return it }
        }
        return listOfNotNull(channel.tvgName, channel.name)
            .firstNotNullOfOrNull { byName[normalizeName(it).ifEmpty { return@firstNotNullOfOrNull null }] }
    }

    fun programmes(channel: IptvChannel): List<EpgProgramme> =
        keyFor(channel)?.let { data.programmes[it] }.orEmpty()

    fun nowNext(channel: IptvChannel, nowMs: Long): NowNext? {
        val list = programmes(channel).takeIf { it.isNotEmpty() } ?: return null
        return nowNext(list, nowMs)
    }

    companion object {
        /** Текущая и следующая передачи в отсортированном списке (бинарный поиск). */
        fun nowNext(list: List<EpgProgramme>, nowMs: Long): NowNext {
            var lo = 0
            var hi = list.size - 1
            var idx = -1 // последняя передача, начавшаяся не позже nowMs
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (list[mid].startMs <= nowMs) { idx = mid; lo = mid + 1 } else hi = mid - 1
            }
            val now = list.getOrNull(idx)?.takeIf { it.isOn(nowMs) }
            val next = list.getOrNull(idx + 1)
            return NowNext(now, next)
        }

        internal fun baseId(id: String): String = id.substringBefore('@').lowercase()

        private val BRACKETS = Regex("\\([^)]*\\)|\\[[^]]*]")
        private val QUALITY = Regex("\\b(fhd|uhd|hd|sd|hq|4k|8k|hevc|h265|orig|backup|50fps)\\b")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}+]")

        /** «Russia-24 HD (1080p) [Geo-blocked]» → «russia24». */
        fun normalizeName(name: String): String =
            name.lowercase()
                .replace('ё', 'е')
                .replace(BRACKETS, " ")
                .replace(QUALITY, " ")
                .replace(NON_ALNUM, "")
    }
}

/** Какие каналы XMLTV нужны плейлисту: по tvg-id и по названиям. */
class EpgFilter(channels: Collection<IptvChannel>) {
    private val ids = HashSet<String>()
    private val baseIds = HashSet<String>()
    private val names = HashSet<String>()

    init {
        channels.forEach { c ->
            c.tvgId?.let { ids += it.lowercase(); baseIds += EpgGuide.baseId(it) }
            listOfNotNull(c.tvgName, c.name).map(EpgGuide::normalizeName).filter { it.isNotEmpty() }.forEach { names += it }
        }
    }

    fun wantsId(id: String): Boolean = id.lowercase() in ids || EpgGuide.baseId(id) in baseIds || EpgGuide.normalizeName(id) in names

    fun wants(id: String, displayNames: List<String>): Boolean =
        wantsId(id) || displayNames.any { EpgGuide.normalizeName(it) in names }
}

/**
 * Потоковый разбор XMLTV: в памяти остаются только передачи нужных каналов
 * в окне [nowMs − pastMs, nowMs + futureMs]. Поддерживается gzip (по сигнатуре).
 */
object XmltvParser {
    const val DEFAULT_PAST_MS = 6 * 3_600_000L
    const val DEFAULT_FUTURE_MS = 24 * 3_600_000L
    private const val MAX_PER_CHANNEL = 300
    private const val MAX_DESCRIPTION = 300

    /** Если поток сжат gzip — распаковывает, иначе возвращает как есть. */
    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }

    fun parse(
        input: InputStream,
        parser: XmlPullParser,
        nowMs: Long,
        filter: EpgFilter? = null,
        pastMs: Long = DEFAULT_PAST_MS,
        futureMs: Long = DEFAULT_FUTURE_MS,
    ): EpgData {
        parser.setInput(maybeGunzip(input), null)
        return parseWith(parser, nowMs, filter, pastMs, futureMs)
    }

    fun parse(reader: Reader, parser: XmlPullParser, nowMs: Long, filter: EpgFilter? = null): EpgData {
        parser.setInput(reader)
        return parseWith(parser, nowMs, filter, DEFAULT_PAST_MS, DEFAULT_FUTURE_MS)
    }

    private fun parseWith(p: XmlPullParser, nowMs: Long, filter: EpgFilter?, pastMs: Long, futureMs: Long): EpgData {
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        val from = nowMs - pastMs
        val to = nowMs + futureMs
        val wanted = HashSet<String>()
        val names = HashMap<String, List<String>>()
        val progs = HashMap<String, ArrayList<EpgProgramme>>()

        try {
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> {
                            val id = p.getAttributeValue(null, "id").orEmpty()
                            val displayNames = readChannelNames(p)
                            if (id.isNotEmpty() && (filter == null || filter.wants(id, displayNames))) {
                                wanted += id
                                names[id] = displayNames
                            }
                        }
                        "programme" -> {
                            val id = p.getAttributeValue(null, "channel").orEmpty()
                            val start = parseTime(p.getAttributeValue(null, "start"))
                            val stop = parseTime(p.getAttributeValue(null, "stop"))
                            val keep = id.isNotEmpty() && start != null &&
                                (filter == null || id in wanted || filter.wantsId(id)) &&
                                (stop ?: start) > from && start < to
                            if (keep) {
                                readProgramme(p, start!!, stop)?.let { prog ->
                                    progs.getOrPut(id) { ArrayList() }.add(prog)
                                }
                            } else {
                                skip(p)
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (e: XmlPullParserException) {
            if (progs.isEmpty()) throw IOException("XMLTV: ${e.message}", e)
        } catch (e: IOException) {
            // Оборванная загрузка: оставляем то, что успели разобрать.
            if (progs.isEmpty()) throw e
        }

        val result = progs.mapValues { (_, list) ->
            list.sortBy { it.startMs }
            fillStops(list).distinctBy { it.startMs }.take(MAX_PER_CHANNEL)
        }
        return EpgData(result, names)
    }

    /** Передачи без `stop` заканчиваются в начале следующей (или через час). */
    private fun fillStops(list: List<EpgProgramme>): List<EpgProgramme> =
        list.mapIndexed { i, prog ->
            if (prog.stopMs > prog.startMs) prog
            else prog.copy(stopMs = list.getOrNull(i + 1)?.startMs?.takeIf { it > prog.startMs } ?: (prog.startMs + 3_600_000L))
        }

    private fun readChannelNames(p: XmlPullParser): List<String> {
        val result = ArrayList<String>(2)
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "display-name") {
                p.nextText().trim().takeIf { it.isNotEmpty() }?.let(result::add)
            }
            event = p.next()
        }
        return result
    }

    private fun readProgramme(p: XmlPullParser, start: Long, stop: Long?): EpgProgramme? {
        var title: String? = null
        var subTitle: String? = null
        var desc: String? = null
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> { val t = p.nextText().trim(); if (title.isNullOrEmpty()) title = t }
                    "sub-title" -> { val t = p.nextText().trim(); if (subTitle.isNullOrEmpty()) subTitle = t }
                    "desc" -> { val t = p.nextText().trim(); if (desc.isNullOrEmpty()) desc = t }
                }
            }
            event = p.next()
        }
        val name = title?.ifEmpty { null } ?: return null
        val description = listOfNotNull(subTitle?.ifEmpty { null }, desc?.ifEmpty { null }).joinToString(". ")
            .ifEmpty { null }?.let { if (it.length > MAX_DESCRIPTION) it.take(MAX_DESCRIPTION - 1) + "…" else it }
        return EpgProgramme(start, stop ?: 0L, name, description)
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Время XMLTV: `20240115183000 +0300`, `20240115183000`, `202401151830 -0500`, `20240115183000Z`.
     * Без часового пояса — UTC.
     */
    fun parseTime(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.length < 12) return null
        var i = 0
        while (i < s.length && s[i].isDigit()) i++
        val digits = s.substring(0, i)
        if (digits.length < 12) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null

        val tz = s.substring(i).trim()
        val offsetMin = when {
            tz.isEmpty() || tz.equals("Z", true) || tz.equals("UTC", true) || tz.equals("GMT", true) -> 0
            (tz[0] == '+' || tz[0] == '-') && tz.length >= 5 -> {
                val body = tz.substring(1).replace(":", "")
                val h = body.take(2).toIntOrNull() ?: return null
                val m = body.drop(2).take(2).toIntOrNull() ?: 0
                (h * 60 + m) * if (tz[0] == '-') -1 else 1
            }
            else -> 0
        }
        val days = daysFromCivil(year, month, day)
        val utcSeconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMin * 60L
        return utcSeconds * 1000L
    }

    /** Дни от 1970-01-01 для григорианской даты (алгоритм Х. Хиннанта). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}

/** Компактный кэш телепрограммы на диске: строки с табуляцией. */
object EpgCodec {
    private const val HEADER = "#P2160-EPG 1"

    fun encode(data: EpgData, out: Appendable) {
        out.append(HEADER).append('\n')
        data.channelNames.forEach { (id, names) ->
            out.append('C').append('\t').append(esc(id))
            names.forEach { out.append('\t').append(esc(it)) }
            out.append('\n')
        }
        data.programmes.forEach { (id, list) ->
            list.forEach { p ->
                out.append('P').append('\t').append(esc(id)).append('\t').append(p.startMs.toString()).append('\t')
                    .append(p.stopMs.toString()).append('\t').append(esc(p.title)).append('\t').append(esc(p.description.orEmpty()))
                    .append('\n')
            }
        }
    }

    fun encode(data: EpgData): String = StringBuilder().also { encode(data, it) }.toString()

    fun decode(lines: Sequence<String>): EpgData {
        val names = LinkedHashMap<String, List<String>>()
        val progs = LinkedHashMap<String, ArrayList<EpgProgramme>>()
        var first = true
        for (line in lines) {
            if (first) { first = false; if (line != HEADER) return EpgData.EMPTY; continue }
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "C" -> if (parts.size >= 2) names[unesc(parts[1])] = parts.drop(2).map(::unesc)
                "P" -> if (parts.size >= 6) {
                    val start = parts[2].toLongOrNull() ?: continue
                    val stop = parts[3].toLongOrNull() ?: continue
                    progs.getOrPut(unesc(parts[1])) { ArrayList() } += EpgProgramme(start, stop, unesc(parts[4]), unesc(parts[5]).ifEmpty { null })
                }
            }
        }
        return EpgData(progs, names)
    }

    fun decode(text: String): EpgData = decode(text.lineSequence())

    private fun esc(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val sb = StringBuilder(s.length + 8)
        s.forEach { c ->
            when (c) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unesc(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; 'r' -> '\r'; else -> s[i + 1] })
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }
}
