package tv.p2160.desktop.media

/** Both axes are written. A boolean `||` would skip the height when the width changes. */
fun assignSurface(width: Int, height: Int, maxW: Int = 1920, maxH: Int = 1080): Pair<Int, Int> =
    width.coerceIn(0, maxW) to height.coerceIn(0, maxH)
