package tv.p2160.app

import java.net.Inet4Address
import java.net.InetAddress

/** Адрес, который пришёл из mDNS, уже разобранный — чтобы выбрать его без сети и без Android NSD. */
data class HostCandidate(
    val address: String,
    val ipv4: Boolean,
    val linkLocal: Boolean,
    /** Имя из обратного DNS, если оно не совпадает с сырым адресом. */
    val hostName: String? = null,
)

/**
 * Какой адрес подставить в SMB из ответа mDNS.
 * IPv4 (не link-local, затем любой) важнее hostname, hostname важнее IPv6.
 * Link-local IPv6 (`fe80::…%wlan0`) в поле адреса не попадает: SMB по нему с телефона обычно не открывается,
 * а выглядит как мусор. Если IPv4 нет — имя хоста или `<имя службы>.local`.
 */
object SmbDiscovery {
    fun preferredHost(candidates: List<HostCandidate>, serviceName: String): String? {
        candidates.firstOrNull { it.ipv4 && !it.linkLocal && it.address.isNotBlank() }?.address?.let { return stripZone(it) }
        candidates.firstOrNull { it.ipv4 && it.address.isNotBlank() }?.address?.let { return stripZone(it) }
        candidates.firstNotNullOfOrNull { usableName(it.hostName, it.address) }?.let { return it }
        val slug = serviceName.substringBefore(" (").trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
        if (slug.isNotEmpty() && ':' !in slug) return "$slug.local"
        return null
    }

    fun candidate(address: InetAddress): HostCandidate {
        val raw = stripZone(address.hostAddress.orEmpty())
        val named = runCatching { address.hostName }.getOrNull()
        return HostCandidate(
            address = raw,
            ipv4 = address is Inet4Address,
            linkLocal = address.isLinkLocalAddress,
            hostName = named,
        )
    }

    private fun usableName(name: String?, rawAddress: String): String? {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return null
        if (n.equals(stripZone(rawAddress), ignoreCase = true)) return null
        if (':' in n || '%' in n) return null
        return n
    }

    private fun stripZone(host: String): String = host.substringBefore('%')
}
