package tv.p2160.desktop.media

/** Прошедшее и оставшееся время. Отрицательный остаток начинается с дефиса. */
fun formatClock(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "--:--"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatRemaining(positionSec: Double, durationSec: Double): String {
    if (durationSec <= 0) return ""
    return "-" + formatClock((durationSec - positionSec).coerceAtLeast(0.0))
}

/**
 * Предлагать продолжение, если это не самое начало и не уже досмотренный хвост.
 * Правило хвоста то же, что у Android: больше 97% или, у ролика от 10 минут, меньше 30 с до конца.
 */
fun shouldOfferResume(positionSec: Double, durationSec: Double): Boolean {
    if (positionSec < 10.0) return false
    if (durationSec <= 0.0) return true
    if (positionSec / durationSec > 0.97) return false
    if (durationSec >= 600.0 && durationSec - positionSec < 30.0) return false
    return true
}

val VIDEO_EXTENSIONS = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "mpg", "mpeg",
    "flv", "vob", "3gp", "ogv", "divx", "rmvb", "hevc",
)
val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "ape", "ac3", "eac3", "mka",
)
val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "sub")

fun java.io.File.isMediaFile(): Boolean {
    val ext = extension.lowercase()
    return ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS
}

/** `Movie.ru.srt` рядом с `Movie.mkv`. `Movie2.srt` не подходит. */
fun subtitleSidecars(video: java.io.File): List<java.io.File> {
    val dir = video.parentFile ?: return emptyList()
    val base = video.nameWithoutExtension
    return dir.listFiles()?.filter { file ->
        file.isFile && file.extension.lowercase() in SUBTITLE_EXTENSIONS && file.name.startsWith("$base.")
    }?.sortedBy { it.name.lowercase() }.orEmpty()
}
