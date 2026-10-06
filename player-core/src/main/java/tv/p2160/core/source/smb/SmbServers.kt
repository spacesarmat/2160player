package tv.p2160.core.source.smb

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Сохранённый SMB-сервер (общая папка NAS/ПК). */
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    /** Имя общей папки, например `NAS` для `\\192.168.1.10\NAS`. */
    val share: String,
    /** Стартовая подпапка внутри share. */
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    /** Корень сервера как URI: `smb://host/share/path`. */
    val rootUri: Uri get() = SmbPath(host, share, path).toUri()

    companion object {
        /** Только хост из адреса: `\\nas`, `smb://nas/`, `192.168.1.10`. */
        fun parseHost(raw: String): String? =
            raw.trim().removePrefix("smb:").replace('\\', '/').trim('/').split('/').firstOrNull { it.isNotBlank() }

        /**
         * Разбор адреса в любом привычном виде:
         * `\\192.168.1.10\NAS\data`, `//nas/NAS`, `smb://nas/NAS/movies`, `192.168.1.10/NAS`.
         */
        fun parseAddress(raw: String): SmbPath? {
            val cleaned = raw.trim()
                .removePrefix("smb:")
                .replace('\\', '/')
                .trim('/')
            val parts = cleaned.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            return SmbPath(parts[0], parts[1], parts.drop(2).joinToString("/"))
        }
    }
}

/** Путь внутри SMB: хост, share и путь с прямыми слешами (без ведущего). */
data class SmbPath(val host: String, val share: String, val path: String) {
    val name: String get() = path.substringAfterLast('/').ifEmpty { share }
    val parent: SmbPath? get() = if (path.isEmpty()) null else copy(path = path.substringBeforeLast('/', ""))

    fun child(name: String) = copy(path = if (path.isEmpty()) name else "$path/$name")

    /** Путь в формате smbj (обратные слеши). */
    val smbjPath: String get() = path.replace('/', '\\')

    fun toUri(): Uri = Uri.Builder().scheme("smb").authority(host)
        .appendPath(share)
        .apply { path.split('/').filter { it.isNotEmpty() }.forEach(::appendPath) }
        .build()

    companion object {
        fun fromUri(uri: Uri): SmbPath? {
            if (uri.scheme?.lowercase() != "smb") return null
            val host = uri.host ?: return null
            val segments = uri.pathSegments
            if (segments.isEmpty()) return null
            return SmbPath(host, segments[0], segments.drop(1).joinToString("/"))
        }
    }
}

/** Хранилище серверов. Пароли лежат в приватных настройках приложения. */
class SmbServers private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("p2160_smb", Context.MODE_PRIVATE)
    private val _servers = MutableStateFlow(read())
    val servers: StateFlow<List<SmbServer>> = _servers.asStateFlow()

    fun save(server: SmbServer) {
        val list = _servers.value.filterNot { it.id == server.id } + server
        write(list)
    }

    fun delete(id: String) = write(_servers.value.filterNot { it.id == id })

    /** Учётные данные для хоста/share: точное совпадение share важнее совпадения только по хосту. */
    fun credentialsFor(host: String, share: String): SmbServer? =
        _servers.value.firstOrNull { it.host.equals(host, true) && it.share.equals(share, true) }
            ?: _servers.value.firstOrNull { it.host.equals(host, true) }

    private fun read(): List<SmbServer> = runCatching {
        val arr = JSONArray(prefs.getString("servers", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SmbServer(
                id = o.getString("id"),
                name = o.optString("name"),
                host = o.getString("host"),
                share = o.getString("share"),
                path = o.optString("path"),
                username = o.optString("user"),
                password = o.optString("pass"),
                domain = o.optString("domain"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<SmbServer>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id).put("name", s.name).put("host", s.host).put("share", s.share)
                    .put("path", s.path).put("user", s.username).put("pass", s.password).put("domain", s.domain)
            )
        }
        prefs.edit().putString("servers", arr.toString()).apply()
        _servers.value = list
    }

    companion object {
        @Volatile private var instance: SmbServers? = null
        fun get(context: Context): SmbServers =
            instance ?: synchronized(this) { instance ?: SmbServers(context).also { instance = it } }
    }
}
