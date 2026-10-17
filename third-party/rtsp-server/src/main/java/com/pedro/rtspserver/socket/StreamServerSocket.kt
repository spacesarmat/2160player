package com.pedro.rtspserver.socket

import android.os.Build
import com.pedro.common.socket.base.SocketType
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.toJavaAddress
import kotlinx.coroutines.Dispatchers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class StreamServerSocket(
    private val type: SocketType
) {
    private var ktorServer: io.ktor.network.sockets.ServerSocket? = null
    private var javaServer: ServerSocket? = null
    private var selectorManager: SelectorManager? = null

    /**
     * [address] == null — все интерфейсы, как раньше.
     * Иначе только этот адрес (частный IPv4 или link-local/ULA).
     */
    suspend fun create(port: Int, address: InetAddress? = null) {
        when (type) {
            SocketType.KTOR -> {
                selectorManager?.close()
                val manager = SelectorManager(Dispatchers.IO)
                selectorManager = manager
                val tcp = aSocket(manager).tcp()
                ktorServer = if (address == null) {
                    tcp.bind("0.0.0.0", port)
                } else {
                    tcp.bind(io.ktor.network.sockets.InetSocketAddress(address.hostAddress ?: "0.0.0.0", port))
                }
            }
            SocketType.JAVA -> {
                javaServer = if (address == null) {
                    ServerSocket(port)
                } else {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(address, port))
                    }
                }
            }
        }
    }

    fun localPort(): Int = when (type) {
        SocketType.JAVA -> javaServer?.localPort ?: 0
        SocketType.KTOR -> (ktorServer?.localAddress?.toJavaAddress() as? InetSocketAddress)?.port ?: 0
    }

    suspend fun accept(): ClientSocket {
        return when (type) {
            SocketType.KTOR -> {
                val socket = ktorServer?.accept() ?: throw IllegalStateException("Server not available")
                val address = socket.remoteAddress.toJavaAddress() as InetSocketAddress
                val hostAddress = address.address?.hostAddress ?: run {
                    val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) address.hostString else null
                    host ?: "0.0.0.0"
                }
                ClientSocket(
                    host = hostAddress,
                    port = address.port,
                    socket = TcpStreamClientSocketKtor(socket, hostAddress, address.port),
                    local = (socket.localAddress.toJavaAddress() as? InetSocketAddress)?.address,
                )
            }
            SocketType.JAVA -> {
                val socket = javaServer?.accept() ?: throw IllegalStateException("Server not available")
                val hostAddress = socket.inetAddress.hostAddress ?: "0.0.0.0"
                ClientSocket(
                    host = hostAddress,
                    port = socket.port,
                    socket = TcpStreamClientSocketJava(socket),
                    local = socket.localAddress,
                )
            }
        }
    }

    fun close() {
        try {
            when (type) {
                SocketType.KTOR -> {
                    ktorServer?.close()
                    ktorServer = null
                    selectorManager?.close()
                    selectorManager = null
                }
                SocketType.JAVA -> {
                    javaServer?.close()
                    javaServer = null
                }
            }
        } catch (_: Exception) {}
    }
}
