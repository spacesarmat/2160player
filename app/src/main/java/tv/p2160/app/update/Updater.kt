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
import tv.p2160.core.i18n.I18n
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Квота неавторизованного api.github.com исчерпана (60 запросов в час на IP).
 * [resetEpochSeconds] - `X-RateLimit-Reset`, [retryAfterSeconds] - числовой `Retry-After`.
 */
internal class GitHubRateLimit(
    val resetEpochSeconds: Long?,
    val retryAfterSeconds: Long? = null,
) : Exception() {
    fun minutes(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Int =
        Updater.retryMinutes(resetEpochSeconds, retryAfterSeconds, nowEpochSeconds)
}

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
    data class Failed(val message: String, val retryMinutes: Int? = null) : UpdateState
}

/**
 * Автообновление из GitHub Releases: проверка последнего релиза, загрузка APK под ABI
 * устройства и установка через [PackageInstaller] (система спросит подтверждение).
 */
object Updater {
    const val REPO = "spacesarmat/2160player"
    /** Пакет релизных сборок: только его APK из GitHub Releases можно поставить поверх установленного. */
    const val RELEASE_PACKAGE = "tv.p2160.player"
    private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

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
        val info = runCatching { fetchLatest(context) }.getOrElse { return }
        // Неудачная проверка (сеть, лимит GitHub) не отодвигает следующую попытку на 12 часов.
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        if (info != null && info.version != p.getString("skipped", null) && _state.value == UpdateState.Idle) {
            _state.value = UpdateState.Available(info)
        }
    }

    /** Проверка по кнопке: показывает и «у вас последняя версия», и ошибки. */
    suspend fun check(context: Context) {
        _state.value = UpdateState.Checking
        _state.value = runCatching { fetchLatest(context) }.fold(
            onSuccess = { if (it != null) UpdateState.Available(it) else UpdateState.UpToDate },
            onFailure = { failed(it) },
        )
    }

    suspend fun download(context: Context, info: UpdateInfo) {
        // Отладочная сборка (tv.p2160.player.debug) - другой пакет: Android не поставит релиз поверх неё
        // (INSTALL_FAILED_INVALID_APK). Открываем страницу релиза - релиз ставится отдельным приложением.
        if (context.packageName != RELEASE_PACKAGE) {
            _state.value = UpdateState.Idle
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(info.pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        _state.value = UpdateState.Downloading(info, 0f)
        try {
            val apk = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "update").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "2160player-${info.version}.apk")
                val conn = open(info.apkUrl)
                try {
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
                } finally {
                    conn.disconnect()
                }
                file
            }
            _state.value = UpdateState.Installing
            withContext(Dispatchers.IO) { install(context, apk) }
        } catch (e: Exception) {
            _state.value = failed(e)
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
            // Если приложение само себя ставило - обновление пройдёт без лишнего диалога.
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
    private suspend fun fetchLatest(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$REPO/releases/latest"
        val response = apiGet(context, "latest", url)
        if (response.rateLimited) {
            return@withContext fetchLatestFromRedirect(context, response.rateLimit ?: GitHubRateLimit(null))
        }
        val json = JSONObject(response.body)
        releaseFromApi(json, Build.SUPPORTED_ABIS.toList())
    }

    /**
     * api.github.com ответил 403/429. Страница `/releases/latest` не входит в эту квоту:
     * без следования редиректу тег читается из `Location` (`…/releases/tag/vX.Y.Z`).
     */
    private fun fetchLatestFromRedirect(context: Context, limit: GitHubRateLimit): UpdateInfo? {
        val tag = runCatching { latestTagFromWeb() }.getOrNull() ?: throw limit
        val version = tag.removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        val names = assetProbeNames(version, Build.SUPPORTED_ABIS.toList())
        val asset = runCatching { firstExistingAsset(version, names) }.getOrNull() ?: throw limit
        return UpdateInfo(
            version = version,
            notes = I18n.get(context).current["update.notes_fallback"],
            apkUrl = asset.first,
            apkSize = asset.second,
            pageUrl = "https://github.com/$REPO/releases/tag/$tag",
        )
    }

    /** Удалить скачанный APK обновления: после установки он больше не нужен (вызывается при запуске). */
    fun cleanup(context: Context) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        File(context.cacheDir, "update").listFiles()?.forEach { it.delete() }
    }

    /**
     * Общий ARM-APK (иначе универсальный) релиза [version]: ссылка и размер.
     * Нужен «Поделиться приложением», когда установлен APK под одну архитектуру.
     */
    internal suspend fun sharedAsset(context: Context, version: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        val response = apiGet(context, "tag_$version", "https://api.github.com/repos/$REPO/releases/tags/v$version")
        if (response.rateLimited) {
            val limit = response.rateLimit ?: GitHubRateLimit(null)
            val names = listOf(assetFileName(version, "arm"), assetFileName(version, "universal"))
            return@withContext runCatching { firstExistingAsset(version, names) }.getOrNull() ?: throw limit
        }
        sharedFromApi(JSONObject(response.body), version)
    }

    internal fun open(url: String, vararg headers: Pair<String, String>): HttpURLConnection {
        val conn = connect(url, headers = headers.toList())
        val code = conn.responseCode
        if (code !in 200..299) {
            val limit = rateLimitOrNull(code, conn)
            conn.disconnect()
            if (limit != null) throw limit
            error("HTTP $code")
        }
        return conn
    }

    private fun failed(e: Throwable): UpdateState.Failed =
        if (e is GitHubRateLimit) UpdateState.Failed("", e.minutes())
        else UpdateState.Failed(e.message ?: e.javaClass.simpleName)

    /** GET к API с `If-None-Match`. 304 не тратит квоту: тело берётся из сохранённого ответа. */
    private fun apiGet(context: Context, cacheKey: String, url: String): ApiBody {
        val (etag, cached) = readCache(context, cacheKey)
        val headers = buildList {
            add("Accept" to "application/vnd.github+json")
            if (!etag.isNullOrBlank() && !cached.isNullOrBlank()) add("If-None-Match" to etag)
        }
        val response = exchange(url, headers = headers)
        if (response.code == 304) {
            if (!cached.isNullOrBlank()) return ApiBody(cached, rateLimited = false, rateLimit = null)
            val fresh = exchange(url, headers = listOf("Accept" to "application/vnd.github+json"))
            if (fresh.code in 200..299) {
                writeCache(context, cacheKey, fresh.etag, fresh.body)
                return ApiBody(fresh.body, rateLimited = false, rateLimit = null)
            }
            if (isApiRateLimit(fresh.code)) return ApiBody("", rateLimited = true, rateLimit = fresh.rateLimit())
            error("HTTP ${fresh.code}")
        }
        if (response.code in 200..299) {
            writeCache(context, cacheKey, response.etag, response.body)
            return ApiBody(response.body, rateLimited = false, rateLimit = null)
        }
        if (isApiRateLimit(response.code)) return ApiBody("", rateLimited = true, rateLimit = response.rateLimit())
        error("HTTP ${response.code}")
    }

    private fun releaseFromApi(json: JSONObject, abis: List<String>): UpdateInfo? {
        val version = json.getString("tag_name").removePrefix("v")
        if (!isNewer(version, currentVersion)) return null
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
        val apk = pickAsset(apks.map { it.getString("name") }, abis)
            ?.let { name -> apks.first { it.getString("name") == name } }
            ?: error("no APK in release $version")
        return UpdateInfo(
            version = version,
            notes = plainText(json.optString("body")),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
            pageUrl = json.optString("html_url"),
        )
    }

    private fun sharedFromApi(json: JSONObject, version: String): Pair<String, Long> {
        val assets = json.getJSONArray("assets")
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.getString("name").endsWith(".apk") }
        val apk = apks.firstOrNull { "-arm-" in it.getString("name") }
            ?: apks.firstOrNull { "universal" in it.getString("name") }
            ?: error("no shared APK in release $version")
        return apk.getString("browser_download_url") to apk.optLong("size")
    }

    private fun latestTagFromWeb(): String? {
        val response = exchange("https://github.com/$REPO/releases/latest", followRedirects = false, readBody = false)
        return tagFromLatestLocation(response.location)
    }

    /** Первый существующий файл: HEAD, без скачивания. Имена - в порядке [assetProbeNames] / [pickAsset]. */
    private fun firstExistingAsset(version: String, names: List<String>): Pair<String, Long>? {
        for (name in names) {
            val url = releaseAssetUrl(version, name)
            val length = headLength(url) ?: continue
            return url to length
        }
        return null
    }

    private fun headLength(url: String): Long? {
        val response = exchange(url, method = "HEAD", readBody = false)
        val exists = response.code in 200..299 ||
            (response.code in REDIRECT_CODES && !response.location.isNullOrBlank())
        if (!exists) return null
        return response.length.coerceAtLeast(0)
    }

    private fun readCache(context: Context, key: String): Pair<String?, String?> {
        val p = prefs(context)
        return p.getString("etag_$key", null) to p.getString("body_$key", null)
    }

    private fun writeCache(context: Context, key: String, etag: String?, body: String) {
        prefs(context).edit().putString("etag_$key", etag).putString("body_$key", body).apply()
    }

    private fun connect(
        url: String,
        method: String = "GET",
        followRedirects: Boolean = true,
        headers: List<Pair<String, String>> = emptyList(),
    ): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = followRedirects
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "2160Player/$currentVersion")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun exchange(
        url: String,
        method: String = "GET",
        followRedirects: Boolean = true,
        headers: List<Pair<String, String>> = emptyList(),
        readBody: Boolean = true,
    ): RawHttp {
        val conn = connect(url, method, followRedirects, headers)
        return try {
            val code = conn.responseCode
            val body = if (!readBody || method == "HEAD") "" else runCatching {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            RawHttp(
                code = code,
                etag = conn.getHeaderField("ETag"),
                remaining = conn.getHeaderField("X-RateLimit-Remaining"),
                reset = conn.getHeaderField("X-RateLimit-Reset"),
                retryAfter = conn.getHeaderField("Retry-After"),
                location = conn.getHeaderField("Location"),
                body = body,
                length = conn.contentLengthLong,
            )
        } finally {
            conn.disconnect()
        }
    }

    private data class ApiBody(val body: String, val rateLimited: Boolean, val rateLimit: GitHubRateLimit?)

    private data class RawHttp(
        val code: Int,
        val etag: String?,
        val remaining: String?,
        val reset: String?,
        val retryAfter: String?,
        val location: String?,
        val body: String,
        val length: Long,
    ) {
        fun rateLimit(): GitHubRateLimit = rateLimitOrNull(code, remaining, reset, retryAfter)
            ?: GitHubRateLimit(null)
    }

    /**
     * Тег из `Location` редиректа `…/releases/latest` → `…/releases/tag/vX.Y.Z`.
     * Абсолютный и относительный URL, хвостовой слэш и query отбрасываются.
     */
    fun tagFromLatestLocation(location: String?): String? {
        if (location.isNullOrBlank()) return null
        val path = location.substringBefore('?').substringBefore('#')
        val marker = "/releases/tag/"
        val at = path.lastIndexOf(marker)
        if (at < 0) return null
        val tag = path.substring(at + marker.length).trim().trim('/')
        if (tag.isEmpty() || '/' in tag) return null
        return tag
    }

    /** Имя APK релиза: `2160player-<версия>-<arm|abi|universal>-release.apk`. */
    fun assetFileName(version: String, token: String): String = "2160player-$version-$token-release.apk"

    /** Прямая ссылка на уже известное имя файла, без API. */
    fun releaseAssetUrl(version: String, fileName: String): String =
        "https://github.com/$REPO/releases/download/v$version/$fileName"

    /** Прямая ссылка на файл релиза: `…/download/vX.Y.Z/2160player-X.Y.Z-<token>-release.apk`. */
    fun assetDownloadUrl(version: String, token: String): String =
        releaseAssetUrl(version, assetFileName(version, token))

    /**
     * Имена для проверки HEAD в том же порядке, что [pickAsset]:
     * на ARM сначала общий `-arm-`, затем ABI устройства, затем `universal`.
     */
    fun assetProbeNames(version: String, abis: List<String>): List<String> {
        val tokens = LinkedHashSet<String>()
        if (abis.any { it.startsWith("arm") }) tokens += "arm"
        abis.filter { it.isNotBlank() }.forEach { tokens += it }
        tokens += "universal"
        return tokens.map { assetFileName(version, it) }
    }

    /**
     * Минуты до `X-RateLimit-Reset` (или до конца `Retry-After`).
     * Без заголовков - час, окно неавторизованной квоты. Минимум одна минута.
     */
    fun retryMinutes(resetEpochSeconds: Long?, retryAfterSeconds: Long?, nowEpochSeconds: Long): Int {
        val until = when {
            resetEpochSeconds != null -> resetEpochSeconds
            retryAfterSeconds != null -> nowEpochSeconds + retryAfterSeconds
            else -> nowEpochSeconds + 60 * 60
        }
        val seconds = (until - nowEpochSeconds).coerceAtLeast(0)
        return ((seconds + 59) / 60).toInt().coerceAtLeast(1)
    }

    /** Описание релиза из Markdown в простой текст для диалога. */
    fun plainText(markdown: String): String = markdown
        .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("""(?m)^#+\s*"""), "")
        .replace(Regex("(?m)^- "), "• ")
        .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
        .trim()

    /** APK под первую поддерживаемую ABI устройства, иначе универсальный. */
    fun pickAsset(names: List<String>, abis: List<String>): String? =
        // ARM-устройствам - общий ARM-APK: установленный файл потом подойдёт и для «Поделиться» на любой телефон/ТВ.
        names.firstOrNull { "-arm-" in it }?.takeIf { abis.any { it.startsWith("arm") } }
            ?: abis.firstNotNullOfOrNull { abi -> names.firstOrNull { "-$abi-" in it } }
            ?: names.firstOrNull { "universal" in it }

    private fun isApiRateLimit(code: Int): Boolean = code == 403 || code == 429

    private fun rateLimitOrNull(code: Int, conn: HttpURLConnection): GitHubRateLimit? =
        rateLimitOrNull(
            code,
            conn.getHeaderField("X-RateLimit-Remaining"),
            conn.getHeaderField("X-RateLimit-Reset"),
            conn.getHeaderField("Retry-After"),
        )

    /**
     * 403/429 api.github.com - квота (обычно `X-RateLimit-Remaining: 0` или `Retry-After`).
     * Для скачивания APK тот же код без этих заголовков остаётся обычной ошибкой HTTP.
     */
    private fun rateLimitOrNull(code: Int, remaining: String?, reset: String?, retryAfter: String?): GitHubRateLimit? {
        if (!isApiRateLimit(code)) return null
        val limited = remaining?.trim() == "0" || !retryAfter.isNullOrBlank()
        if (!limited) return null
        return GitHubRateLimit(reset?.trim()?.toLongOrNull(), retryAfter?.trim()?.toLongOrNull())
    }

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
