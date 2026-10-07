package tv.p2160.app

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections

/**
 * Маленький загрузчик логотипов каналов: память (LRU) → диск (`cacheDir/logos`) → сеть.
 * Картинки уменьшаются при декодировании до нужного размера, параллельно не больше 4 загрузок.
 */
object LogoLoader {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private val memory = LruCache<String, ImageBitmap>(300)
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val permits = Semaphore(4)

    fun peek(url: String?): ImageBitmap? = url?.let { memory.get(it) }

    suspend fun load(context: Context, url: String, sizePx: Int): ImageBitmap? {
        memory.get(url)?.let { return it }
        if (url in failed) return null
        return withContext(Dispatchers.IO) {
            permits.withPermit {
                memory.get(url) ?: runCatching { fetch(context, url, sizePx) }.getOrNull()
                    ?.also { memory.put(url, it) }
                    ?: null.also { failed += url }
            }
        }
    }

    private fun fetch(context: Context, url: String, sizePx: Int): ImageBitmap? {
        val dir = File(context.cacheDir, "logos").apply { mkdirs() }
        val file = File(dir, sha1(url))
        if (!file.exists()) {
            val tmp = File(dir, file.name + ".tmp")
            when {
                url.startsWith("http://", true) || url.startsWith("https://", true) -> download(url, tmp)
                url.startsWith("content:", true) || url.startsWith("file:", true) ->
                    context.contentResolver.openInputStream(android.net.Uri.parse(url))?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                else -> return null
            }
            if (!tmp.exists() || !tmp.renameTo(file)) return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { file.delete(); return null }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx / 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return bitmap.asImageBitmap()
    }

    private fun download(url: String, target: File) {
        var current = URL(url)
        repeat(5) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = URL(current, conn.getHeaderField("Location") ?: return)
                    return@repeat
                }
                if (code !in 200..299) return
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) { out.close(); target.delete(); return }
                            out.write(buffer, 0, n)
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Логотип канала; пока грузится или его нет — [placeholder]. */
@Composable
fun ChannelLogo(url: String?, sizePx: Int, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState(LogoLoader.peek(url), url) {
        if (value == null && !url.isNullOrBlank()) value = LogoLoader.load(context, url, sizePx)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Fit) else placeholder()
    }
}
