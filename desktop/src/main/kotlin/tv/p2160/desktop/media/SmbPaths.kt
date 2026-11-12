package tv.p2160.desktop.media

object SmbPaths {
    fun child(parent: String, name: String): String {
        val base = parent.trim('\\', '/')
        return if (base.isEmpty()) name else "$base\\$name"
    }
}

/**
 * At the share root, Up returns to the list captured at connect time.
 * Inside a folder the caller reloads the parent and this returns null.
 */
fun shareListAfterUp(path: String, knownShares: List<String>): List<String>? =
    if (path.isEmpty()) knownShares else null
