package tv.p2160.torrent

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** В какой сети работают торренты. */
enum class NetworkMode {
    /** Качать и раздавать по любой сети. */
    ANY,
    /** Просмотр — по любой сети; фоновые раздачи и отдача — только по безлимитной (Wi-Fi). */
    SEED_WIFI,
    /** Только по безлимитной сети; на мобильном интернете — после согласия ([TorrentEngine.allowMobileData]). */
    WIFI_ONLY,
}

/** Когда прекращать раздачу скачанного торрента (если его сейчас не смотрят). */
enum class SeedPolicy {
    ALWAYS,
    /** Пока не отдано столько же, сколько весят выбранные файлы (рейтинг 1:1). */
    RATIO,
    /** 24 часа раздачи после завершения загрузки. */
    DAY,
    /** Сразу после завершения загрузки. */
    NEVER,
}

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
    val network: NetworkMode = NetworkMode.SEED_WIFI,
    val seedPolicy: SeedPolicy = SeedPolicy.ALWAYS,
    /** Фоновые раздачи — только на зарядке (устройства без батареи считаются заряжающимися). */
    val seedOnlyCharging: Boolean = false,
    /** Ограничение загрузки по сети с оплатой трафика, КБ/с; 0 — без ограничения. */
    val mobileDownloadLimitKb: Int = 0,
) {
    /**
     * Настройки сессии с учётом сети: по сети с оплатой трафика (кроме режима [NetworkMode.ANY]) отдача
     * урезается до 16 КБ/с — совсем выключить её у качающегося торрента нельзя (пиры перестанут отдавать
     * нам), и действует [mobileDownloadLimitKb].
     */
    fun sessionConfig(metered: Boolean = false): SessionConfig = SessionConfig(
        maxConnections = maxConnections,
        uploadLimit = if (metered && network != NetworkMode.ANY) METERED_UPLOAD_LIMIT else uploadLimitKb * 1024,
        downloadLimit = if (metered) mobileDownloadLimitKb * 1024 else 0,
    )

    fun cleanupPolicy(): CleanupPolicy = CleanupPolicy(
        maxAgeDays = maxAgeDays,
        sizeLimitBytes = cacheLimitGb.toLong() * 1024 * 1024 * 1024,
        keepFiles = keepFiles,
    )

    companion object {
        val CACHE_LIMITS = listOf(5, 10, 20, 50, 100, 0)
        val MAX_AGE_DAYS = listOf(1, 3, 7, 14, 30)
        val CONNECTIONS = listOf(50, 80, 100, 200, 400)
        val UPLOAD_LIMITS = listOf(0, 100, 500, 1024, 5 * 1024)
        val MOBILE_DOWNLOAD_LIMITS = listOf(0, 512, 1024, 2 * 1024, 5 * 1024)
        /** Отдача по сети с оплатой трафика, байт/с. */
        const val METERED_UPLOAD_LIMIT = 16 * 1024
    }
}

/** Хранение [TorrentPrefs] в собственных SharedPreferences. */
class TorrentSettings internal constructor(context: Context) {
    private val prefs = context.getSharedPreferences("p2160_torrent", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<TorrentPrefs> = _state.asStateFlow()

    /** Слабое устройство: по умолчанию меньше соединений (каждое — буферы в памяти). */
    val lowMemory: Boolean = tv.p2160.core.api.DeviceProfile.lowMemory(context)

    private fun load(): TorrentPrefs {
        val d = TorrentPrefs(maxConnections = if (lowMemory) 80 else 200)
        return TorrentPrefs(
            cacheLimitGb = prefs.getInt("cache_limit_gb", d.cacheLimitGb),
            keepFiles = prefs.getBoolean("keep_files", d.keepFiles),
            maxAgeDays = prefs.getInt("max_age_days", d.maxAgeDays),
            maxConnections = prefs.getInt("max_connections", d.maxConnections),
            uploadLimitKb = prefs.getInt("upload_limit_kb", d.uploadLimitKb),
            network = runCatching { NetworkMode.valueOf(prefs.getString("network", null)!!) }.getOrDefault(d.network),
            seedPolicy = runCatching { SeedPolicy.valueOf(prefs.getString("seed_policy", null)!!) }.getOrDefault(d.seedPolicy),
            seedOnlyCharging = prefs.getBoolean("seed_only_charging", d.seedOnlyCharging),
            mobileDownloadLimitKb = prefs.getInt("mobile_download_limit_kb", d.mobileDownloadLimitKb),
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
            putString("network", next.network.name)
            putString("seed_policy", next.seedPolicy.name)
            putBoolean("seed_only_charging", next.seedOnlyCharging)
            putInt("mobile_download_limit_kb", next.mobileDownloadLimitKb)
        }
        _state.value = next
    }
}
