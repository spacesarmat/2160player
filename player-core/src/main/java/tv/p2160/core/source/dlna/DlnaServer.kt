package tv.p2160.core.source.dlna

/** Иконка устройства из описания UPnP. */
data class DlnaIcon(val url: String, val mimeType: String?, val width: Int, val height: Int)

/** Медиасервер DLNA/UPnP с сервисом ContentDirectory. */
data class DlnaServer(
    /** `uuid:...` — устойчивый идентификатор устройства. */
    val udn: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val deviceType: String,
    /** Адрес XML-описания (LOCATION из SSDP). */
    val location: String,
    val contentDirectoryControlUrl: String,
    /** Полный serviceType ContentDirectory (`...:ContentDirectory:1`) — нужен в SOAPAction. */
    val contentDirectoryType: String,
    val icons: List<DlnaIcon> = emptyList(),
) {
    /** Хост сервера (для подписи в списке). */
    val host: String get() = runCatching { java.net.URI(location).host }.getOrNull().orEmpty()

    /** Лучшая иконка для списка: PNG/JPEG размером ближе к [target] пикселям. */
    fun bestIcon(target: Int = 120): DlnaIcon? = icons
        .filter { it.mimeType == null || it.mimeType.contains("png", true) || it.mimeType.contains("jpeg", true) || it.mimeType.contains("jpg", true) }
        .ifEmpty { icons }
        .minByOrNull { icon ->
            val size = maxOf(icon.width, icon.height).takeIf { it > 0 } ?: target
            // Крупнее нужного — небольшой штраф, мельче — больший.
            (if (size >= target) size - target else (target - size) * 2) + if (icon.mimeType?.contains("png", true) == true) 0 else 1
        }
}

/** Разбор XML-описания устройства (`LOCATION`). */
object DeviceDescriptionParser {

    private const val CONTENT_DIRECTORY = "ContentDirectory"

    /**
     * Загрузка сервера по известному адресу описания — для серверов, чей SSDP не доходит
     * (например, Jellyfin в Docker с bridge-сетью: `http://nas:8096/dlna/<serverId>/description.xml`).
     */
    fun load(location: String, http: DlnaHttp = UrlConnectionHttp()): DlnaServer? {
        val res = http.get(location)
        if (res.code !in 200..299) throw DlnaException("HTTP ${res.code}", res.code)
        return parse(res.body, location)
    }

    /**
     * Возвращает сервер, если в описании (включая вложенные устройства) есть ContentDirectory,
     * иначе `null` — это роутер, телевизор-рендерер и т.п.
     */
    fun parse(xml: String, location: String): DlnaServer? {
        val root = XmlLite.parse(xml).let { doc -> doc.child("root") ?: doc.children.firstOrNull() } ?: return null
        val base = root.childText("URLBase") ?: location
        val topDevice = root.child("device") ?: root.find("device") ?: return null
        val device = findWithContentDirectory(topDevice) ?: return null
        val service = device.child("serviceList")?.children("service").orEmpty()
            .first { it.childText("serviceType")?.contains(CONTENT_DIRECTORY, true) == true }
        val control = resolveUrl(base, service.childText("controlURL")) ?: return null
        // Иконки и имя берём у найденного устройства, а при их отсутствии — у корневого.
        val icons = parseIcons(device, base).ifEmpty { parseIcons(topDevice, base) }
        val udn = device.childText("UDN") ?: topDevice.childText("UDN") ?: location
        return DlnaServer(
            udn = udn,
            friendlyName = device.childText("friendlyName") ?: topDevice.childText("friendlyName") ?: hostOf(location),
            manufacturer = device.childText("manufacturer") ?: topDevice.childText("manufacturer"),
            modelName = device.childText("modelName") ?: topDevice.childText("modelName"),
            deviceType = device.childText("deviceType").orEmpty(),
            location = location,
            contentDirectoryControlUrl = control,
            contentDirectoryType = service.childText("serviceType") ?: "urn:schemas-upnp-org:service:ContentDirectory:1",
            icons = icons,
        )
    }

    private fun findWithContentDirectory(device: XmlNode): XmlNode? {
        val has = device.child("serviceList")?.children("service").orEmpty()
            .any { it.childText("serviceType")?.contains(CONTENT_DIRECTORY, true) == true }
        if (has) return device
        return device.child("deviceList")?.children("device").orEmpty().firstNotNullOfOrNull(::findWithContentDirectory)
    }

    private fun parseIcons(device: XmlNode, base: String): List<DlnaIcon> =
        device.child("iconList")?.children("icon").orEmpty().mapNotNull { icon ->
            val url = resolveUrl(base, icon.childText("url")) ?: return@mapNotNull null
            DlnaIcon(
                url = url,
                mimeType = icon.childText("mimetype"),
                width = icon.childText("width")?.toIntOrNull() ?: 0,
                height = icon.childText("height")?.toIntOrNull() ?: 0,
            )
        }

    private fun hostOf(location: String) = runCatching { java.net.URI(location).host }.getOrNull() ?: location
}
