package tv.p2160.core.resume

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
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
    SQLiteOpenHelper(context.applicationContext, "p2160_resume.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
        Covers.delete(appContext, key)
        _changes.value++
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
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

    companion object {
        @Volatile private var instance: ResumeStore? = null

        fun get(context: Context): ResumeStore =
            instance ?: synchronized(this) { instance ?: ResumeStore(context).also { instance = it } }

        /**
         * Ключ файла. У сетевых ссылок отбрасываем query-строку: в ней часто живут
         * одноразовые токены, из-за которых позиция «терялась» бы при каждом запуске.
         */
        fun keyFor(uri: Uri): String = when (uri.scheme?.lowercase()) {
            "http", "https" -> "${uri.scheme}://${uri.host}${uri.path.orEmpty()}"
            else -> uri.toString()
        }

        /** Считаем фильм досмотренным, если осталось меньше 3% или 30 секунд. */
        fun isFinished(positionMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (durationMs - positionMs < 30_000 || positionMs > durationMs * 0.97)
    }
}
