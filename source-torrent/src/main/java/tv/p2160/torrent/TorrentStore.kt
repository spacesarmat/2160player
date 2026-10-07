package tv.p2160.torrent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Сохранённый торрент: что нужно, чтобы показать его в списке и восстановить в сессии. */
data class StoredTorrent(
    val id: String,
    val name: String,
    val magnet: String?,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val primaryFile: Int = -1,
    /** Скачано байт (последнее известное значение). */
    val bytesDone: Long = 0,
    val totalSize: Long = 0,
    /** Есть ли данные на диске (после очистки — false, остаются только метаданные). */
    val hasData: Boolean = false,
    val files: List<TorrentFile> = emptyList(),
)

/**
 * Индекс торрентов: `filesDir/torrents/index.json` + `<id>.torrent` (метаданные) и
 * `<id>.resume` (быстрое возобновление). Все методы потокобезопасны.
 */
internal class TorrentStore(private val dir: File) {
    private val indexFile = File(dir, "index.json")
    private val items = LinkedHashMap<String, StoredTorrent>()

    init {
        dir.mkdirs()
        runCatching { if (indexFile.exists()) parse(indexFile.readText()).forEach { items[it.id] = it } }
    }

    @Synchronized fun all(): List<StoredTorrent> = items.values.toList()
    @Synchronized operator fun get(id: String): StoredTorrent? = items[id]

    @Synchronized
    fun put(item: StoredTorrent) {
        items[item.id] = item
        save()
    }

    @Synchronized
    fun update(id: String, transform: (StoredTorrent) -> StoredTorrent): StoredTorrent? {
        val old = items[id] ?: return null
        val next = transform(old)
        if (next != old) {
            items[id] = next
            save()
        }
        return next
    }

    @Synchronized
    fun delete(id: String) {
        items.remove(id)
        torrentFile(id).delete()
        resumeFile(id).delete()
        save()
    }

    fun torrentFile(id: String) = File(dir, "$id.torrent")
    fun resumeFile(id: String) = File(dir, "$id.resume")

    private fun save() {
        val arr = JSONArray()
        items.values.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                t.magnet?.let { put("magnet", it) }
                put("addedAt", t.addedAt)
                put("lastOpenedAt", t.lastOpenedAt)
                put("primaryFile", t.primaryFile)
                put("bytesDone", t.bytesDone)
                put("totalSize", t.totalSize)
                put("hasData", t.hasData)
                put("files", JSONArray().apply {
                    t.files.forEach { f -> put(JSONObject().put("i", f.index).put("p", f.path).put("s", f.size)) }
                })
            })
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    private fun parse(text: String): List<StoredTorrent> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val files = o.optJSONArray("files")
            StoredTorrent(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                magnet = o.optString("magnet").takeIf { it.isNotEmpty() },
                addedAt = o.optLong("addedAt"),
                lastOpenedAt = o.optLong("lastOpenedAt"),
                primaryFile = o.optInt("primaryFile", -1),
                bytesDone = o.optLong("bytesDone"),
                totalSize = o.optLong("totalSize"),
                hasData = o.optBoolean("hasData"),
                files = if (files == null) emptyList() else (0 until files.length()).map { j ->
                    val f = files.getJSONObject(j)
                    TorrentFile(f.getInt("i"), f.getString("p"), f.getLong("s"))
                },
            )
        }
    }
}
