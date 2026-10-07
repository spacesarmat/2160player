package tv.p2160.torrent

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Пользовательские настройки торрентов. */
data class TorrentPrefs(
    /** Лимит кэша, ГБ; 0 — без лимита. */
    val cacheLimitGb: Int = 20,
    /** Хранить скачанное после просмотра (иначе данные удаляются через сутки). */
    val keepFiles: Boolean = false,
    /** Удалять торренты, не открывавшиеся столько дней. */
    val maxAgeDays: Int = 7,
    val maxConnections: Int = 200,
    /** Ограничение отдачи, КБ/с; 0 — без ограничения. */
    val uploadLimitKb: Int = 0,
) {
    fun sessionConfig(): SessionConfig = SessionConfig(maxConnections = maxConnections, uploadLimit = uploadLimitKb * 1024)

    fun cleanupPolicy(): CleanupPolicy = CleanupPolicy(
        maxAgeDays = maxAgeDays,
        sizeLimitBytes = cacheLimitGb.toLong() * 1024 * 1024 * 1024,
        keepFiles = keepFiles,
    )

    companion object {
        val CACHE_LIMITS = listOf(5, 10, 20, 50, 100, 0)
        val MAX_AGE_DAYS = listOf(1, 3, 7, 14, 30)
        val CONNECTIONS = listOf(50, 100, 200, 400)
        val UPLOAD_LIMITS = listOf(0, 100, 500, 1024, 5 * 1024)
    }
}

/** Хранение [TorrentPrefs] в собственных SharedPreferences. */
class TorrentSettings internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences("p2160_torrent", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TorrentPrefs> = _state.asStateFlow()

    private fun load(): TorrentPrefs {
        val d = TorrentPrefs()
        return TorrentPrefs(
            cacheLimitGb = prefs.getInt("cache_limit_gb", d.cacheLimitGb),
            keepFiles = prefs.getBoolean("keep_files", d.keepFiles),
            maxAgeDays = prefs.getInt("max_age_days", d.maxAgeDays),
            maxConnections = prefs.getInt("max_connections", d.maxConnections),
            uploadLimitKb = prefs.getInt("upload_limit_kb", d.uploadLimitKb),
        )
    }

    fun update(transform: (TorrentPrefs) -> TorrentPrefs) {
        val next = transform(_state.value)
        prefs.edit {
            putInt("cache_limit_gb", next.cacheLimitGb)
            putBoolean("keep_files", next.keepFiles)
            putInt("max_age_days", next.maxAgeDays)
            putInt("max_connections", next.maxConnections)
            putInt("upload_limit_kb", next.uploadLimitKb)
        }
        _state.value = next
    }
}
