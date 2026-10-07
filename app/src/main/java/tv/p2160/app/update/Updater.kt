package tv.p2160.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.p2160.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Опубликованный релиз на GitHub, подходящий этому устройству. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Автообновление из GitHub Releases: проверка последнего релиза, загрузка APK под ABI
 * устройства и установка через [PackageInstaller] (система спросит подтверждение).
 */
object Updater {
    const val REPO = "spacesarmat/2160player"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    private fun prefs(context: Context) = context.getSharedPreferences("p2160_update", Context.MODE_PRIVATE)

    /** В отладочных сборках по умолчанию выключено: у них другой пакет и подпись. */
    fun isAutoCheck(context: Context) = prefs(context).getBoolean("auto", !BuildConfig.DEBUG)
    fun setAutoCheck(context: Context, value: Boolean) = prefs(context).edit().putBoolean("auto", value).apply()

    fun skip(context: Context, version: String) {
        prefs(context).edit().putString("skipped", version).apply()
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    /** Тихая проверка при запуске: не чаще раза в 12 часов, пропущенную версию не предлагаем. */
    suspend fun autoCheck(context: Context) {
        if (!isAutoCheck(context)) return
        val p = prefs(context)
        if (System.currentTimeMillis() - p.getLong("last", 0) < CHECK_INTERVAL_MS) return
        val info = runCatching { fetchLatest() }.getOrNull()
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        if (info != null && info.version != p.getString("skipped", null) && _state.value == UpdateState.Idle) {
            _state.value = UpdateState.Available(info)
        }
    }

    /** Проверка по кнопке: показывает и «у вас последняя версия», и ошибки. */
    suspend fun check() {
        _state.value = UpdateState.Checking
        _state.value = runCatching { fetchLatest() }.fold(
            onSuccess = { if (it != null) UpdateState.Available(it) else UpdateState.UpToDate },
            onFailure = { UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    suspend fun download(context: Context, info: UpdateInfo) {
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            val apk = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "2160player-${info.version}.apk")
                val conn = open(info.apkUrl)
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastReport > 256 * 1024) {
                                lastReport = done
                                _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                            }
                        }
                    }
                }
                file
            }
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { install(context, apk) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Idle
            else -> UpdateState.Failed(message ?: "status $status")
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Если приложение само себя ставило — обновление пройдёт без лишнего диалога.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(context, UpdateReceiver::class.java)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }

    /** Последний релиз, если он новее установленной версии, иначе null. */
    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val version = json.getString("tag_name").removePrefix("v")
        if (!isNewer(version, currentVersion)) return@withContext null
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
        val apk = pickAsset(apks.map { it.getString("name") }, Build.SUPPORTED_ABIS.toList())
            ?.let { name -> apks.first { it.getString("name") == name } }
            ?: error("no APK in release $version")
        UpdateInfo(
            version = version,
            notes = json.optString("body").trim(),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
            pageUrl = json.optString("html_url"),
        )
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "2160Player/$currentVersion")
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn
    }

    /** APK под первую поддерживаемую ABI устройства, иначе универсальный. */
    fun pickAsset(names: List<String>, abis: List<String>): String? =
        abis.firstNotNullOfOrNull { abi -> names.firstOrNull { "-$abi-" in it } }
            ?: names.firstOrNull { "universal" in it }

    /** Сравнение версий вида 1.2.3 (суффиксы вроде -beta игнорируются). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
