package tv.p2160.torrent

import java.net.URLDecoder

/**
 * Разобранная magnet-ссылка. Разбор свой (без нативной библиотеки) — чтобы проверить ввод
 * пользователя и показать имя сразу, ещё до запуска сессии.
 */
data class MagnetLink(
    /** v1 info-hash, 40 hex-символов в нижнем регистре (из hex или base32). null — только v2. */
    val infoHashV1: String?,
    /** v2 info-hash (SHA-256), 64 hex-символа. */
    val infoHashV2: String?,
    val displayName: String?,
    val trackers: List<String>,
    val webSeeds: List<String>,
    /** Размер из `xl`, если указан. */
    val exactLength: Long?,
) {
    /** Идентификатор торрента как у libtorrent: v1-хеш или усечённый до 20 байт v2. */
    val id: String get() = infoHashV1 ?: infoHashV2!!.substring(0, 40)

    companion object {
        private val HEX40 = Regex("[0-9a-fA-F]{40}")
        private val BASE32 = Regex("[A-Za-z2-7]{32}")
        private val HEX64 = Regex("[0-9a-fA-F]{64}")

        /** Похоже ли на magnet или «голый» info-hash (так часто копируют с трекеров). */
        fun looksLikeMagnet(text: String): Boolean = parse(text) != null

        /**
         * Разбирает `magnet:?xt=urn:btih:…` или голый хеш (40 hex / 32 base32).
         * null — не magnet или нет корректного info-hash.
         */
        fun parse(input: String): MagnetLink? {
            val text = input.trim()
            if (HEX40.matches(text)) return MagnetLink(text.lowercase(), null, null, emptyList(), emptyList(), null)
            if (BASE32.matches(text)) return MagnetLink(base32ToHex(text), null, null, emptyList(), emptyList(), null)
            if (!text.regionMatches(0, "magnet:?", 0, 8, ignoreCase = true)) return null

            var v1: String? = null
            var v2: String? = null
            var name: String? = null
            var length: Long? = null
            val trackers = mutableListOf<String>()
            val seeds = mutableListOf<String>()
            for (part in text.substring(8).split('&')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                // Ключи бывают с индексом: xt.1, tr.2 …
                val key = part.substring(0, eq).substringBefore('.').lowercase()
                val value = decode(part.substring(eq + 1))
                when (key) {
                    "xt" -> when {
                        value.startsWith("urn:btih:", ignoreCase = true) -> {
                            val h = value.substring(9)
                            when {
                                HEX40.matches(h) -> v1 = v1 ?: h.lowercase()
                                BASE32.matches(h) -> v1 = v1 ?: base32ToHex(h)
                                else -> return null
                            }
                        }
                        value.startsWith("urn:btmh:", ignoreCase = true) -> {
                            // multihash: 0x12 (sha2-256), 0x20 (32 байта), затем хеш
                            val h = value.substring(9)
                            if (h.length == 68 && h.startsWith("1220") && HEX64.matches(h.substring(4))) v2 = v2 ?: h.substring(4).lowercase()
                            else return null
                        }
                    }
                    "dn" -> name = name ?: value.takeIf { it.isNotBlank() }
                    "tr" -> if (value.isNotBlank() && value !in trackers) trackers += value
                    "ws" -> if (value.isNotBlank() && value !in seeds) seeds += value
                    "xl" -> length = value.toLongOrNull()?.takeIf { it >= 0 }
                }
            }
            if (v1 == null && v2 == null) return null
            return MagnetLink(v1, v2, name, trackers, seeds, length)
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** RFC 4648 base32 → hex (20 байт). */
        fun base32ToHex(s: String): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val out = StringBuilder(40)
            var buffer = 0L
            var bits = 0
            for (c in s.uppercase()) {
                val v = alphabet.indexOf(c)
                require(v >= 0) { "bad base32" }
                buffer = (buffer shl 5) or v.toLong()
                bits += 5
                while (bits >= 8) {
                    bits -= 8
                    out.append("%02x".format(((buffer shr bits) and 0xFF).toInt()))
                }
            }
            return out.toString()
        }
    }
}
