package tv.p2160.desktop.media

import tv.p2160.core.iptv.M3uParser

/** HLS master/media playlists and empty lists open as one URL. A channel list does not. */
fun playlistOpensAsStream(text: String, baseUrl: String): Boolean {
    val playlist = M3uParser.parse(text, baseUrl)
    return playlist.looksLikeHls || playlist.channels.isEmpty()
}
