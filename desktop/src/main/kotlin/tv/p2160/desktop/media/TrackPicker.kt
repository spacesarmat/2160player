package tv.p2160.desktop.media

import tv.p2160.desktop.player.MediaTrack

/** Next track id, or null when the choice is off (past the end, or the list is empty). */
fun nextTrackId(tracks: List<MediaTrack>): Int? {
    if (tracks.isEmpty()) return null
    val selected = tracks.indexOfFirst { it.selected }
    return tracks.getOrNull(selected + 1)?.id
}
