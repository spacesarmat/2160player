package tv.p2160.desktop.media

object SmbPaths {
    fun child(parent: String, name: String): String {
        val base = parent.trim('\\', '/')
        return if (base.isEmpty()) name else "$base\\$name"
    }
}
