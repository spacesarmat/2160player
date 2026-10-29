package tv.p2160.torrent

import tv.p2160.core.subtitle.SubtitleSupport

/**
 * Озвучка и субтитры из того же торрента, что и видео.
 * Серия определяется по `S01E01` / `1x01` в имени. Студия берётся из папки под
 * `RUS Sound`, `Audio`, `Rus Subs` и похожими. Язык - из имени файла, иначе из этой папки.
 */
object TorrentSidecars {

    data class Hit(
        val file: TorrentFile,
        val audio: Boolean,
        val language: String?,
        /** Имя студии из папки, например LostFilm. null - папки студии нет. */
        val studio: String?,
    ) {
        /** Стабильный id для переноса выбора на следующую серию: студия, иначе язык. */
        val trackId: String? get() = studio?.let(::studioId) ?: language
    }

    fun match(video: TorrentFile, files: List<TorrentFile>, videoCount: Int): List<Hit> {
        if (!video.isVideo) return emptyList()
        return files.mapNotNull { file -> hit(video, file, videoCount) }
            .sortedWith(compareBy({ it.studio == null }, { it.studio?.lowercase() }, { it.file.path.lowercase() }))
    }

    fun episodeKey(name: String): String? {
        SEASON_EP.find(name)?.let { found ->
            return key(found.groupValues[1], found.groupValues[2])
        }
        NUMBER_X.find(name)?.let { found ->
            return key(found.groupValues[1], found.groupValues[2])
        }
        return null
    }

    fun isAudioFolder(name: String): Boolean {
        val normalized = name.trim().lowercase()
        if (normalized in AUDIO_FOLDERS) return true
        return normalized.endsWith(" sound") || normalized.endsWith(" sounds") ||
            normalized.endsWith(" audio") || normalized.endsWith(" dub") || normalized.endsWith(" dubs")
    }

    fun isSubsFolder(name: String): Boolean {
        val normalized = name.trim().lowercase()
        if (normalized in SUBS_FOLDERS) return true
        return normalized.endsWith(" subs") || normalized.endsWith(" subtitles") || normalized.endsWith(" sub")
    }

    /** `RUS Sound` -> rus, `Audio` -> null. */
    fun folderLanguage(name: String): String? {
        val first = name.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
        if (first.length !in 2..3 || !first.all { it.isLetter() }) return null
        if (first in GENERIC_WORDS) return null
        return first
    }

    internal fun studioId(studio: String): String {
        val slug = studio.lowercase().filter { it.isLetterOrDigit() }
        return slug.ifEmpty { studio.lowercase() }
    }

    private fun hit(video: TorrentFile, file: TorrentFile, videoCount: Int): Hit? {
        if (file.index == video.index) return null
        val audio = file.isAudio
        val text = file.isSubtitle
        if (!audio && !text) return null
        val container = containerFolder(file.path)
        if (container != null) {
            val audioFolder = isAudioFolder(container)
            val subsFolder = isSubsFolder(container)
            if (audio && !audioFolder) return null
            if (text && !subsFolder) return null
        }
        if (!sameItem(video, file, videoCount, container != null)) return null
        val language = SubtitleSupport.languageFromName(file.name) ?: container?.let(::folderLanguage)
        return Hit(file, audio = audio, language = language, studio = studioFolder(file.path))
    }

    private fun sameItem(video: TorrentFile, file: TorrentFile, videoCount: Int, inSidecarFolder: Boolean): Boolean {
        val videoKey = episodeKey(video.name)
        val fileKey = episodeKey(file.name)
        if (videoKey != null && fileKey != null) return videoKey == fileKey
        if (videoKey != null) return stemMatch(video, file)
        if (fileKey != null) return false
        if (stemMatch(video, file)) return true
        return videoCount == 1 && inSidecarFolder
    }

    private fun stemMatch(video: TorrentFile, file: TorrentFile): Boolean {
        val base = video.name.substringBeforeLast('.').lowercase()
        return SubtitleSupport.matchesStem(base, file.name.lowercase())
    }

    private fun containerFolder(path: String): String? =
        folders(path).lastOrNull { isAudioFolder(it) || isSubsFolder(it) }

    private fun studioFolder(path: String): String? {
        val parents = folders(path)
        val at = parents.indexOfLast { isAudioFolder(it) || isSubsFolder(it) }
        if (at < 0 || at + 1 >= parents.size) return null
        val next = parents[at + 1]
        if (isAudioFolder(next) || isSubsFolder(next)) return null
        return next
    }

    private fun folders(path: String): List<String> {
        val parts = path.split('/', '\\').filter { it.isNotEmpty() }
        return if (parts.size <= 1) emptyList() else parts.dropLast(1)
    }

    private fun key(season: String, episode: String): String =
        "s${season.toInt().toString().padStart(2, '0')}e${episode.toInt().toString().padStart(2, '0')}"

    private val SEASON_EP = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[\s._-]*e(\d{1,3})(?:[^a-z0-9]|$)""")
    private val NUMBER_X = Regex("""(?i)(?:^|[^a-z0-9])(\d{1,2})x(\d{2,3})(?:[^a-z0-9]|$)""")

    private val AUDIO_FOLDERS = setOf(
        "audio", "audios", "sound", "sounds", "dub", "dubs", "voice", "voices",
        "озвучка", "озвучки", "звук",
    )
    private val SUBS_FOLDERS = setOf("subs", "sub", "subtitles", "subtitle", "субтитры", "субтитр")
    private val GENERIC_WORDS = AUDIO_FOLDERS + SUBS_FOLDERS
}
