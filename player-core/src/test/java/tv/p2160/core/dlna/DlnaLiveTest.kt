package tv.p2160.core.dlna

import org.junit.Assume.assumeTrue
import org.junit.Test
import tv.p2160.core.source.dlna.ContentDirectory
import tv.p2160.core.source.dlna.DlnaProbe
import tv.p2160.core.source.dlna.DlnaContainer
import tv.p2160.core.source.dlna.DlnaItem
import tv.p2160.core.source.dlna.DlnaServer
import tv.p2160.core.source.dlna.SsdpDiscovery

/**
 * Живой поиск в локальной сети (только чтение). Запуск:
 * `P2160_DLNA_IT=1 ./gradlew :player-core:testDebugUnitTest --tests '*DlnaLiveTest*' -i`
 * Дополнительно `P2160_DLNA_LOCATION=host1,host:port,url` — серверы, не видимые по SSDP (через [DlnaProbe]).
 */
class DlnaLiveTest {

    @Test
    fun discoverAndBrowseRoot() {
        assumeTrue("set P2160_DLNA_IT=1", System.getenv("P2160_DLNA_IT") == "1")
        val locations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val discovered = SsdpDiscovery(onMessage = { m -> m.location?.let { locations += "${m.kind} $it" } }).search(timeoutMs = 6000)
        println("DLNA: SSDP locations seen: ${locations.sorted()}")
        println("DLNA: SSDP found ${discovered.size} server(s)")
        val manual = System.getenv("P2160_DLNA_LOCATION").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { input ->
                runCatching { DlnaProbe.resolve(input) }.onFailure { println("DLNA: $input -> $it") }.getOrNull()
                    .also { println("DLNA: probe '$input' -> ${it?.location ?: "nothing"}") }
            }
        (discovered + manual).distinctBy { it.udn }.forEach(::dump)
    }

    private fun dump(s: DlnaServer) {
        println("DLNA server: ${s.friendlyName} | ${s.manufacturer} | ${s.modelName} | ${s.location} | control=${s.contentDirectoryControlUrl} | icon=${s.bestIcon()?.url}")
        runCatching {
            val cd = ContentDirectory(s)
            val root = cd.browseAll(ContentDirectory.ROOT_ID)
            root.forEach { o ->
                when (o) {
                    is DlnaContainer -> println("  [dir] ${o.title} (id=${o.id}, children=${o.childCount}, class=${o.upnpClass})")
                    is DlnaItem -> println("  [item] ${o.title} ${o.kind} ${o.bestResource?.mimeType}")
                }
            }
            // Спускаемся по первым папкам до видео (не глубже 4 уровней) — проверка разбора элементов.
            var level = root
            repeat(4) {
                val items = level.filterIsInstance<DlnaItem>()
                if (items.isNotEmpty()) {
                    items.take(3).forEach { i ->
                        val r = i.bestResource
                        println("  [item] ${i.title} ${i.kind} mime=${r?.mimeType} size=${r?.size} dur=${r?.durationMs} res=${r?.width}x${r?.height} converted=${r?.isConverted} subs=${i.subtitles.map { it.format + ":" + (it.language ?: "?") }} of ${i.resources.size} res")
                    }
                    return@runCatching
                }
                val next = level.filterIsInstance<DlnaContainer>().firstOrNull { (it.childCount ?: 1) > 0 } ?: return@runCatching
                level = cd.browseAll(next.id, limit = 50)
                println("  ${next.title}/ -> ${level.size} object(s): " + level.take(8).joinToString { it.title })
            }
        }.onFailure { println("  browse failed: $it") }
    }
}
