package tv.p2160.core.source.dlna

/** Одна страница Browse. */
data class BrowsePage(
    val objects: List<DlnaObject>,
    val numberReturned: Int,
    /** 0 — сервер не знает общего числа (так допускает спецификация). */
    val totalMatches: Int,
    val updateId: String? = null,
)

/** Клиент сервиса ContentDirectory: SOAP Browse (BrowseDirectChildren) с постраничной загрузкой. */
class ContentDirectory(
    private val server: DlnaServer,
    private val http: DlnaHttp = UrlConnectionHttp(),
) {

    /** Одна страница детей контейнера [objectId] (`"0"` — корень). */
    fun browse(objectId: String, start: Int = 0, count: Int = PAGE_SIZE): BrowsePage {
        val res = http.post(
            server.contentDirectoryControlUrl,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPAction" to "\"${server.contentDirectoryType}#Browse\"",
                // Samsung-расширение: сервер добавляет sec:CaptionInfoEx (внешние субтитры).
                "getCaptionInfo.sec" to "1",
            ),
            browseEnvelope(server.contentDirectoryType, objectId, start, count),
        )
        return parseBrowseResponse(res)
    }

    /**
     * Все дети контейнера с постраничной загрузкой. Учитывает серверы, которые возвращают
     * TotalMatches=0, игнорируют RequestedCount или StartingIndex. Не более [limit] объектов.
     */
    fun browseAll(objectId: String, pageSize: Int = PAGE_SIZE, limit: Int = 10_000): List<DlnaObject> {
        val out = ArrayList<DlnaObject>()
        val ids = HashSet<String>()
        var start = 0
        while (out.size < limit) {
            val page = browse(objectId, start, pageSize)
            val fresh = page.objects.filter { ids.add(it.id) }
            out += fresh
            val step = maxOf(page.numberReturned, page.objects.size)
            // Пустая страница или сервер вернул то же самое ещё раз (игнорирует StartingIndex).
            if (step == 0 || fresh.isEmpty()) break
            start += step
            if (page.totalMatches > 0 && start >= page.totalMatches) break
            if (page.totalMatches == 0 && step < pageSize) break
        }
        return out.take(limit)
    }

    companion object {
        const val PAGE_SIZE = 200
        const val ROOT_ID = "0"

        fun browseEnvelope(serviceType: String, objectId: String, start: Int, count: Int): String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:Browse xmlns:u=\"${XmlLite.escape(serviceType)}\">" +
                "<ObjectID>${XmlLite.escape(objectId)}</ObjectID>" +
                "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter>" +
                "<StartingIndex>$start</StartingIndex>" +
                "<RequestedCount>$count</RequestedCount>" +
                "<SortCriteria></SortCriteria>" +
                "</u:Browse></s:Body></s:Envelope>"

        /** Разбор ответа Browse или SOAP Fault (бросает [DlnaException]). */
        fun parseBrowseResponse(res: HttpResult): BrowsePage {
            val doc = XmlLite.parse(res.body)
            doc.find("Fault")?.let { fault ->
                val code = fault.find("errorCode")?.text?.trim()?.toIntOrNull()
                val desc = fault.find("errorDescription")?.text?.trim() ?: fault.find("faultstring")?.text?.trim()
                throw DlnaException("UPnP error ${code ?: res.code}: ${desc ?: "unknown"}", code ?: res.code)
            }
            if (res.code !in 200..299) throw DlnaException("HTTP ${res.code}", res.code)
            val response = doc.find("BrowseResponse") ?: throw DlnaException("Invalid Browse response")
            val result = response.child("Result")
            val objects = when {
                result == null -> emptyList()
                // DIDL вложен как XML без экранирования (нарушение спецификации, но встречается).
                result.children.isNotEmpty() -> DidlParser.parse(result.find("DIDL-Lite") ?: result)
                else -> {
                    var didl = result.text.trim()
                    // Двойное экранирование (&amp;lt;DIDL-Lite...) — раскрываем ещё раз.
                    if (didl.startsWith("&lt;")) didl = XmlLite.decodeEntities(didl)
                    if (didl.isEmpty()) emptyList() else DidlParser.parse(didl)
                }
            }
            return BrowsePage(
                objects = objects,
                numberReturned = response.childText("NumberReturned")?.toIntOrNull() ?: objects.size,
                totalMatches = response.childText("TotalMatches")?.toIntOrNull() ?: 0,
                updateId = response.childText("UpdateID"),
            )
        }
    }
}
