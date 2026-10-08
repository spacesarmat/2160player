package tv.p2160.app.handoff

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Только локальная сеть: Wi-Fi, Ethernet, точка доступа и USB-модем самого телефона. Мобильный интернет для
 * трансляций и раздачи файлов не используется — ни в адресах, которые мы показываем, ни для входящих
 * подключений, ни для исходящих (SRT/RTMP).
 */
object Lan {
    /** Интерфейсы точки доступа / модема самого телефона (своей Network в ConnectivityManager у них нет). */
    private val TETHER = Regex("^(ap|swlan|softap|wlan|wigig|rndis|usb|ncm|eth|bt-pan)\\d*.*")

    private fun cm(context: Context) = context.getSystemService(ConnectivityManager::class.java)

    /** Сеть Wi-Fi или Ethernet (без мобильной и VPN поверх неё); null — такой нет. */
    fun network(context: Context): Network? {
        val cm = cm(context) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /** Имена локальных интерфейсов: Wi-Fi/Ethernet по ConnectivityManager + точка доступа/модем, кроме мобильных. */
    fun interfaces(context: Context): Set<String> {
        val cm = cm(context) ?: return emptySet()
        val lan = HashSet<String>()
        val cellular = HashSet<String>()
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            val iface = cm.getLinkProperties(n)?.interfaceName ?: continue
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular += iface
                c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Unit
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> lan += iface
            }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.name !in cellular && TETHER.matches(it.name) }
                .forEach { lan += it.name }
        }
        return lan
    }

    /** IPv4 устройства в локальной сети (сначала Wi-Fi/Ethernet, потом точка доступа); null — локальной сети нет. */
    fun ipv4(context: Context): String? = ipv4s(context).firstOrNull()

    fun ipv4s(context: Context): List<String> {
        val names = interfaces(context)
        val primary = network(context)?.let { cm(context)?.getLinkProperties(it)?.interfaceName }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && it.name in names }
                .sortedBy { if (it.name == primary) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }

    /** Подключение пришло на локальный интерфейс (или с самого устройства), а не через мобильную сеть. */
    fun isLocal(context: Context, local: InetAddress?): Boolean {
        if (local == null) return false
        if (local.isLoopbackAddress) return true
        val ni = runCatching { NetworkInterface.getByInetAddress(local) }.getOrNull() ?: return false
        return ni.name in interfaces(context)
    }

    /**
     * Адрес назначения (только IP-литерал, без DNS) лежит в подсети одного из локальных интерфейсов — туда
     * пакеты уйдут по локальной сети даже без Wi-Fi (например, OBS подключён к точке доступа телефона).
     */
    fun inLocalSubnet(context: Context, host: String): Boolean {
        if (!host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) return false
        val target = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return false
        val names = interfaces(context)
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && it.name in names }.any { ni ->
                ni.interfaceAddresses.any { a ->
                    val addr = a.address as? Inet4Address ?: return@any false
                    val bits = a.networkPrefixLength.toInt().coerceIn(0, 32)
                    val mask = if (bits == 0) 0 else -1 shl (32 - bits)
                    (toInt(addr) and mask) == (toInt(target) and mask)
                }
            }
        }.getOrDefault(false)
    }

    private fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }
}
