package tv.p2160.core.source.dlna

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Известный медиасервер: найденный по SSDP или добавленный вручную. */
data class KnownDlnaServer(val server: DlnaServer, val manual: Boolean)

/**
 * Реестр медиасерверов DLNA: результаты поиска (в памяти) и добавленные вручную (сохраняются).
 * Один экземпляр на процесс — экран обзора находит сервер по [DlnaServer.udn].
 */
class DlnaServers private constructor(context: Context) {
    private val lock = AndroidMulticastLock(context)
    private val prefs = context.applicationContext.getSharedPreferences("p2160_dlna", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val manual = MutableStateFlow(readManual())
    private val discovered = MutableStateFlow<List<DlnaServer>>(emptyList())
    private val _servers = MutableStateFlow(merge())
    val servers: StateFlow<List<KnownDlnaServer>> = _servers.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private var job: Job? = null

    /** Новый цикл поиска (если уже идёт — ничего не делает). */
    fun refresh(timeoutMs: Long = 5000) {
        if (job?.isActive == true) return
        _searching.value = true
        job = scope.launch {
            try {
                DlnaDiscovery.events(lock, timeoutMs).collect { e ->
                    when (e) {
                        is DlnaDiscovery.Event.Found -> discovered.update { list -> list.filterNot { it.udn == e.server.udn } + e.server }
                        is DlnaDiscovery.Event.Lost -> discovered.update { list -> list.filterNot { it.udn == e.udn } }
                    }
                    publish()
                }
            } finally {
                _searching.value = false
            }
        }
    }

    fun find(udn: String): DlnaServer? = _servers.value.firstOrNull { it.server.udn == udn }?.server

    /** Добавляет сервер по адресу (см. [DlnaProbe]). Бросает [DlnaException], если сервер не найден. */
    suspend fun addByAddress(input: String): DlnaServer = withContext(Dispatchers.IO) {
        val server = DlnaProbe.resolve(input) ?: throw DlnaException("not found")
        manual.update { list -> list.filterNot { it.udn == server.udn } + server }
        writeManual()
        publish()
        server
    }

    fun removeManual(udn: String) {
        manual.update { list -> list.filterNot { it.udn == udn } }
        writeManual()
        publish()
    }

    private fun publish() { _servers.value = merge() }

    private fun merge(): List<KnownDlnaServer> {
        val m = manual.value.map { KnownDlnaServer(it, manual = true) }
        val d = discovered.value.filter { s -> m.none { it.server.udn == s.udn } }.map { KnownDlnaServer(it, manual = false) }
        return (d.sortedBy { it.server.friendlyName.lowercase() } + m)
    }

    private fun readManual(): List<DlnaServer> = runCatching {
        val arr = JSONArray(prefs.getString("manual", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val icons = o.optJSONArray("icons")
            DlnaServer(
                udn = o.getString("udn"),
                friendlyName = o.getString("name"),
                manufacturer = o.optString("manufacturer").ifEmpty { null },
                modelName = o.optString("model").ifEmpty { null },
                deviceType = o.optString("type"),
                location = o.getString("location"),
                contentDirectoryControlUrl = o.getString("control"),
                contentDirectoryType = o.getString("service"),
                icons = (0 until (icons?.length() ?: 0)).map { j ->
                    val ic = icons!!.getJSONObject(j)
                    DlnaIcon(ic.getString("url"), ic.optString("mime").ifEmpty { null }, ic.optInt("w"), ic.optInt("h"))
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun writeManual() {
        val arr = JSONArray()
        manual.value.forEach { s ->
            val icons = JSONArray()
            s.icons.forEach { icons.put(JSONObject().put("url", it.url).put("mime", it.mimeType.orEmpty()).put("w", it.width).put("h", it.height)) }
            arr.put(
                JSONObject().put("udn", s.udn).put("name", s.friendlyName).put("manufacturer", s.manufacturer.orEmpty())
                    .put("model", s.modelName.orEmpty()).put("type", s.deviceType).put("location", s.location)
                    .put("control", s.contentDirectoryControlUrl).put("service", s.contentDirectoryType).put("icons", icons)
            )
        }
        prefs.edit { putString("manual", arr.toString()) }
    }

    companion object {
        @Volatile private var instance: DlnaServers? = null
        fun get(context: Context): DlnaServers =
            instance ?: synchronized(this) { instance ?: DlnaServers(context).also { instance = it } }
    }
}
