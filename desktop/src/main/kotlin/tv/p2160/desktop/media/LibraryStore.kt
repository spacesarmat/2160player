package tv.p2160.desktop.media

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

data class RecentItem(
    val kind: String,
    val title: String,
    val key: String,
    val payload: String,
)

data class SmbSaved(
    val host: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
)

/**
 * Недавние файлы, позиция и сохранённые SMB-серверы.
 * Файл лежит в каталоге данных пользователя и не шифруется.
 */
class LibraryStore(val root: File = defaultDataDir()) {
    private val file = File(root, "library.json")
    private val positions = HashMap<String, Double>()
    private val recent = ArrayList<RecentItem>()
    private val servers = ArrayList<SmbSaved>()
    var volume: Int = 80
        private set

    val torrentDir: File = File(root, "torrents").apply { mkdirs() }

    init {
        root.mkdirs()
        ownerOnlyDir(root)
        load()
    }

    private var lastSaveMs = 0L

    fun position(key: String): Double? = synchronized(this) { positions[key] }

    fun rememberPosition(key: String, seconds: Double) = synchronized(this) {
        if (seconds < 1.0) return
        positions[key] = seconds
        val now = System.currentTimeMillis()
        if (lastSaveMs == 0L || now - lastSaveMs >= 5_000) {
            lastSaveMs = now
            save()
        }
    }

    fun flush() = synchronized(this) { save() }

    fun forgetPosition(key: String) = synchronized(this) {
        if (positions.remove(key) != null) save()
    }

    fun rememberVolume(value: Int) = synchronized(this) {
        volume = value.coerceIn(0, 100)
        save()
    }

    fun recent(): List<RecentItem> = synchronized(this) { recent.toList() }

    fun rememberRecent(item: RecentItem) = synchronized(this) {
        recent.removeAll { it.key == item.key }
        recent.add(0, item)
        while (recent.size > 30) recent.removeAt(recent.lastIndex)
        save()
    }

    fun servers(): List<SmbSaved> = synchronized(this) { servers.toList() }

    fun rememberServer(server: SmbSaved) = synchronized(this) {
        servers.removeAll { it.host.equals(server.host, true) && it.user == server.user }
        servers.add(0, server)
        save()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val json = JSONObject(file.readText())
            volume = json.optInt("volume", 80).coerceIn(0, 100)
            val pos = json.optJSONObject("positions") ?: JSONObject()
            pos.keys().forEach { key -> positions[key] = pos.getDouble(key) }
            val items = json.optJSONArray("recent") ?: JSONArray()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                recent += RecentItem(
                    kind = o.getString("kind"),
                    title = o.optString("title"),
                    key = o.getString("key"),
                    payload = o.optString("payload"),
                )
            }
            val smb = json.optJSONArray("smb") ?: JSONArray()
            for (i in 0 until smb.length()) {
                val o = smb.getJSONObject(i)
                servers += SmbSaved(
                    host = o.getString("host"),
                    user = o.optString("user"),
                    password = o.optString("password"),
                    domain = o.optString("domain"),
                )
            }
        }
    }

    private fun save() {
        val json = JSONObject()
        json.put("volume", volume)
        val pos = JSONObject()
        positions.forEach { (key, value) -> pos.put(key, value) }
        json.put("positions", pos)
        val items = JSONArray()
        recent.forEach { item ->
            items.put(
                JSONObject()
                    .put("kind", item.kind)
                    .put("title", item.title)
                    .put("key", item.key)
                    .put("payload", item.payload),
            )
        }
        json.put("recent", items)
        val smb = JSONArray()
        servers.forEach { server ->
            smb.put(
                JSONObject()
                    .put("host", server.host)
                    .put("user", server.user)
                    .put("password", server.password)
                    .put("domain", server.domain),
            )
        }
        json.put("smb", smb)
        root.mkdirs()
        ownerOnlyDir(root)
        val tmp = File(root, "library.json.tmp")
        writePrivate(tmp, json.toString(2))
        if (!tmp.renameTo(file)) {
            writePrivate(file, tmp.readText())
            tmp.delete()
        }
    }

    private fun writePrivate(target: File, text: String) {
        if (target.exists() && !target.delete()) target.writeText("")
        val path = target.toPath()
        if (posix()) {
            val perms = setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            )
            if (!Files.exists(path)) {
                Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(perms))
            } else {
                Files.setPosixFilePermissions(path, perms)
            }
            Files.writeString(
                path,
                text,
                java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
            )
        } else {
            target.writeText(text)
            ownerOnly(target)
        }
    }

    private fun ownerOnlyDir(dir: File) {
        if (!dir.isDirectory) return
        if (posix()) {
            Files.setPosixFilePermissions(
                dir.toPath(),
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        } else {
            ownerOnly(dir)
            dir.setExecutable(false, false)
            dir.setExecutable(true, true)
        }
    }

    private fun posix(): Boolean =
        java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    private fun ownerOnly(target: File) {
        target.setReadable(false, false)
        target.setReadable(true, true)
        target.setWritable(false, false)
        target.setWritable(true, true)
    }
}

fun defaultDataDir(): File {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()
    val dir = when {
        os.contains("win") -> File(System.getenv("APPDATA") ?: home.path, "2160player")
        os.contains("mac") -> File(home, "Library/Application Support/2160player")
        else -> File(home, ".local/share/2160player")
    }
    dir.mkdirs()
    return dir
}
