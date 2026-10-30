package tv.p2160.core.resume

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import tv.p2160.core.api.ExternalAudio
import tv.p2160.core.api.ExternalSubtitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Сохранённое состояние просмотра одного файла. */
data class ResumeEntry(
    val key: String,
    val uri: String,
    val title: String?,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean = false,
    val audioLanguage: String? = null,
    val audioLabel: String? = null,
    val textLanguage: String? = null,
    val textLabel: String? = null,
    val textDisabled: Boolean = false,
    val speed: Float = 1f,
    val subtitleDelayMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * Хранилище позиций остановки и выбранных дорожек на SQLite.
 * Сделано без Room, чтобы библиотека не тянула kapt/ksp в чужие проекты.
 */
class ResumeStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "p2160_resume.db", null, DB_VERSION) {
    private val appContext = context.applicationContext


    private val _changes = MutableStateFlow(0L)
    /** Меняется при каждой записи; удобно для обновления UI истории. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE history (
                key TEXT PRIMARY KEY,
                uri TEXT NOT NULL,
                title TEXT,
                position INTEGER NOT NULL,
                duration INTEGER NOT NULL,
                finished INTEGER NOT NULL DEFAULT 0,
                audio_lang TEXT, audio_label TEXT,
                text_lang TEXT, text_label TEXT,
                text_disabled INTEGER NOT NULL DEFAULT 0,
                speed REAL NOT NULL DEFAULT 1,
                sub_delay INTEGER NOT NULL DEFAULT 0,
                updated INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX history_updated ON history(updated DESC)")
        createExternalSubs(db)
        createExternalAudio(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createExternalSubs(db)
        if (oldVersion == 2) addTrackColumns(db, "external_subs")
        if (oldVersion < 3) createExternalAudio(db)
    }

    /** Внешние субтитры, подключённые к файлу вручную или из Intent. Пустой список - не запоминали. */
    fun externalSubtitles(videoKey: String): List<ExternalSubtitle> =
        readableDatabase.query(
            "external_subs", null, "video_key = ?", arrayOf(videoKey), null, null, "rowid",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ExternalSubtitle(
                            uri = Uri.parse(c.getString(c.getColumnIndexOrThrow("uri"))),
                            name = c.opt("name"),
                            language = c.opt("language"),
                            select = c.getInt(c.getColumnIndexOrThrow("selected")) == 1,
                            id = c.opt("track_id"),
                            mimeType = c.opt("mime"),
                            delayMs = c.optLong("delay_ms"),
                            charset = c.opt("charset"),
                        )
                    )
                }
            }
        }

    /** Записать или заменить один файл субтитров. [ExternalSubtitle.select] снимает выбор с остальных. */
    fun upsertExternalSubtitle(videoKey: String, sub: ExternalSubtitle) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (sub.select) {
                db.update("external_subs", ContentValues().apply { put("selected", 0) }, "video_key = ?", arrayOf(videoKey))
            }
            db.insertWithOnConflict(
                "external_subs",
                null,
                ContentValues().apply {
                    put("video_key", videoKey)
                    put("uri", sub.uri.toString())
                    put("name", sub.name)
                    put("language", sub.language)
                    put("selected", if (sub.select) 1 else 0)
                    put("track_id", sub.id)
                    put("mime", sub.mimeType)
                    put("delay_ms", sub.delayMs)
                    put("charset", sub.charset)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Какой внешний файл включён. `null` - ни один (дорожка выключена или выбрана встроенная). */
    fun setExternalSubtitleSelected(videoKey: String, uri: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("external_subs", ContentValues().apply { put("selected", 0) }, "video_key = ?", arrayOf(videoKey))
            if (uri != null) {
                db.update(
                    "external_subs",
                    ContentValues().apply { put("selected", 1) },
                    "video_key = ? AND uri = ?",
                    arrayOf(videoKey, uri),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun externalAudio(videoKey: String): List<ExternalAudio> =
        readableDatabase.query(
            "external_audio", null, "video_key = ?", arrayOf(videoKey), null, null, "rowid",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ExternalAudio(
                            uri = Uri.parse(c.getString(c.getColumnIndexOrThrow("uri"))),
                            name = c.opt("name"),
                            language = c.opt("language"),
                            select = c.getInt(c.getColumnIndexOrThrow("selected")) == 1,
                            id = c.opt("track_id"),
                            mimeType = c.opt("mime"),
                            delayMs = c.optLong("delay_ms"),
                        )
                    )
                }
            }
        }

    fun upsertExternalAudio(videoKey: String, audio: ExternalAudio) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (audio.select) {
                db.update("external_audio", ContentValues().apply { put("selected", 0) }, "video_key = ?", arrayOf(videoKey))
            }
            db.insertWithOnConflict(
                "external_audio",
                null,
                ContentValues().apply {
                    put("video_key", videoKey)
                    put("uri", audio.uri.toString())
                    put("name", audio.name)
                    put("language", audio.language)
                    put("selected", if (audio.select) 1 else 0)
                    put("track_id", audio.id)
                    put("mime", audio.mimeType)
                    put("delay_ms", audio.delayMs)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Убрать запомненную внешнюю дорожку. Без uri стирается весь набор этого видео. */
    fun deleteExternalAudio(videoKey: String, uri: String? = null) {
        if (uri == null) {
            writableDatabase.delete("external_audio", "video_key = ?", arrayOf(videoKey))
        } else {
            writableDatabase.delete("external_audio", "video_key = ? AND uri = ?", arrayOf(videoKey, uri))
        }
    }

    fun setExternalAudioSelected(videoKey: String, uri: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("external_audio", ContentValues().apply { put("selected", 0) }, "video_key = ?", arrayOf(videoKey))
            if (uri != null) {
                db.update(
                    "external_audio",
                    ContentValues().apply { put("selected", 1) },
                    "video_key = ? AND uri = ?",
                    arrayOf(videoKey, uri),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun get(key: String): ResumeEntry? =
        readableDatabase.query("history", null, "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.toEntry() else null
        }

    fun recent(limit: Int = 50, includeFinished: Boolean = true): List<ResumeEntry> =
        readableDatabase.query(
            "history", null,
            if (includeFinished) null else "finished = 0", null,
            null, null, "updated DESC", limit.toString(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toEntry()) } }

    fun save(entry: ResumeEntry) {
        val values = ContentValues().apply {
            put("key", entry.key)
            put("uri", entry.uri)
            put("title", entry.title)
            put("position", entry.positionMs)
            put("duration", entry.durationMs)
            put("finished", if (entry.finished) 1 else 0)
            put("audio_lang", entry.audioLanguage)
            put("audio_label", entry.audioLabel)
            put("text_lang", entry.textLanguage)
            put("text_label", entry.textLabel)
            put("text_disabled", if (entry.textDisabled) 1 else 0)
            put("speed", entry.speed)
            put("sub_delay", entry.subtitleDelayMs)
            put("updated", entry.updatedAt)
        }
        writableDatabase.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        _changes.value++
    }

    fun delete(key: String) {
        writableDatabase.delete("history", "key = ?", arrayOf(key))
        writableDatabase.delete("external_subs", "video_key = ?", arrayOf(key))
        writableDatabase.delete("external_audio", "video_key = ?", arrayOf(key))
        Covers.delete(appContext, key)
        _changes.value++
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
        writableDatabase.delete("external_subs", null, null)
        writableDatabase.delete("external_audio", null, null)
        Covers.clear(appContext)
        _changes.value++
    }

    /** Обложка записи (см. [Covers]) или null. */
    fun cover(key: String): java.io.File? = Covers.get(appContext, key)

    private fun Cursor.toEntry() = ResumeEntry(
        key = str("key")!!,
        uri = str("uri")!!,
        title = str("title"),
        positionMs = getLong(getColumnIndexOrThrow("position")),
        durationMs = getLong(getColumnIndexOrThrow("duration")),
        finished = getInt(getColumnIndexOrThrow("finished")) == 1,
        audioLanguage = str("audio_lang"),
        audioLabel = str("audio_label"),
        textLanguage = str("text_lang"),
        textLabel = str("text_label"),
        textDisabled = getInt(getColumnIndexOrThrow("text_disabled")) == 1,
        speed = getFloat(getColumnIndexOrThrow("speed")),
        subtitleDelayMs = getLong(getColumnIndexOrThrow("sub_delay")),
        updatedAt = getLong(getColumnIndexOrThrow("updated")),
    )

    private fun Cursor.str(column: String): String? = getString(getColumnIndexOrThrow(column))

    private fun Cursor.opt(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0) return null
        return getString(index)?.ifBlank { null }
    }

    private fun Cursor.optLong(column: String): Long {
        val index = getColumnIndex(column)
        if (index < 0) return 0
        return getLong(index)
    }

    private fun createExternalSubs(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS external_subs (
                video_key TEXT NOT NULL,
                uri TEXT NOT NULL,
                name TEXT,
                language TEXT,
                selected INTEGER NOT NULL DEFAULT 0,
                track_id TEXT,
                mime TEXT,
                delay_ms INTEGER NOT NULL DEFAULT 0,
                charset TEXT,
                PRIMARY KEY (video_key, uri)
            )
            """.trimIndent()
        )
    }

    private fun createExternalAudio(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS external_audio (
                video_key TEXT NOT NULL,
                uri TEXT NOT NULL,
                name TEXT,
                language TEXT,
                selected INTEGER NOT NULL DEFAULT 0,
                track_id TEXT,
                mime TEXT,
                delay_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (video_key, uri)
            )
            """.trimIndent()
        )
    }

    private fun addTrackColumns(db: SQLiteDatabase, table: String) {
        db.execSQL("ALTER TABLE $table ADD COLUMN track_id TEXT")
        db.execSQL("ALTER TABLE $table ADD COLUMN mime TEXT")
        db.execSQL("ALTER TABLE $table ADD COLUMN delay_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE $table ADD COLUMN charset TEXT")
    }

    companion object {
        private const val DB_VERSION = 3

        @Volatile private var instance: ResumeStore? = null

        fun get(context: Context): ResumeStore =
            instance ?: synchronized(this) { instance ?: ResumeStore(context).also { instance = it } }

        /**
         * Ключ файла. У сетевых ссылок отбрасываем query-строку: в ней часто живут
         * одноразовые токены, из-за которых позиция «терялась» бы при каждом запуске.
         */
        fun keyFor(uri: Uri): String = when (val scheme = uri.scheme?.lowercase()) {
            "http", "https" -> networkKey(scheme, uri.host, uri.port, uri.path) ?: uri.toString()
            else -> uri.toString()
        }

        /**
         * Ключ `http`/`https` без query и фрагмента: хост, нестандартный порт и путь.
         * Порты 80 и 443 не входят в ключ. IPv6 берётся в скобках. null - хоста нет.
         */
        internal fun networkKey(scheme: String, host: String?, port: Int, path: String?): String? {
            if (host.isNullOrEmpty()) return null
            val hostPart = if (':' in host) "[$host]" else host
            val portPart = when {
                port <= 0 -> ""
                scheme == "http" && port == 80 -> ""
                scheme == "https" && port == 443 -> ""
                else -> ":$port"
            }
            return "$scheme://$hostPart$portPart${path.orEmpty()}"
        }

        /**
         * Фильм досмотрен, если пройдено больше 97 % или (у ролика длиннее 30 с) осталось меньше 30 с.
         * Порог в 30 с не применяется к коротким роликам: иначе они считались бы досмотренными с первой секунды
         * и не попадали бы в «Продолжить просмотр».
         */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean {
            if (durationMs <= 0 || positionMs <= 0) return false
            val remaining = durationMs - positionMs
            val nearEndByTime = durationMs > 30_000L && remaining < 30_000L
            val nearEndByRatio = positionMs > durationMs * 0.97
            return nearEndByTime || nearEndByRatio
        }
    }
}
