package tv.p2160.torrent

/**
 * Политика очистки кэша торрентов (чистая функция, проверяется тестами).
 *
 * - Торрент, который не открывали дольше [maxAgeDays], удаляется целиком (если не [keepFiles]).
 * - Без [keepFiles] скачанные данные неактивных торрентов удаляются через [dataGraceHours]
 *   после последнего просмотра; запись (метаданные) остаётся — фильм можно открыть снова.
 * - Лимит размера действует всегда: удаляем данные самых давно открытых неактивных торрентов,
 *   пока суммарный объём не станет ≤ [sizeLimitBytes] (0 — без лимита).
 * - Активные (сейчас воспроизводятся) никогда не трогаем.
 */
data class CleanupPolicy(
    val maxAgeDays: Int = 7,
    val sizeLimitBytes: Long = 20L * 1024 * 1024 * 1024,
    val keepFiles: Boolean = false,
    val dataGraceHours: Int = 24,
) {
    data class Item(
        val id: String,
        val lastOpenedAt: Long,
        /** Байт данных на диске (0 — данных нет). */
        val bytesOnDisk: Long,
        val active: Boolean,
    )

    enum class Action { DELETE_DATA, REMOVE }

    fun plan(items: List<Item>, now: Long): Map<String, Action> {
        val result = LinkedHashMap<String, Action>()
        val dayMs = 24L * 60 * 60 * 1000
        val hourMs = 60L * 60 * 1000
        items.filter { !it.active }.forEach { item ->
            val idle = now - item.lastOpenedAt
            when {
                !keepFiles && maxAgeDays > 0 && idle > maxAgeDays * dayMs -> result[item.id] = Action.REMOVE
                !keepFiles && item.bytesOnDisk > 0 && idle > dataGraceHours * hourMs -> result[item.id] = Action.DELETE_DATA
            }
        }
        if (sizeLimitBytes > 0) {
            var total = items.filter { result[it.id] == null }.sumOf { it.bytesOnDisk }
            items.filter { !it.active && result[it.id] == null && it.bytesOnDisk > 0 }
                .sortedBy { it.lastOpenedAt }
                .forEach { item ->
                    if (total <= sizeLimitBytes) return@forEach
                    result[item.id] = Action.DELETE_DATA
                    total -= item.bytesOnDisk
                }
        }
        return result
    }
}
