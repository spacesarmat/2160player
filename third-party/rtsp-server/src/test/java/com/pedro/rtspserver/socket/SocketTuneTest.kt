package com.pedro.rtspserver.socket

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class SocketTuneTest {
    @Test
    fun acceptedSocketDisablesNagleAndKeepsAPositiveSendBuffer() {
        ServerSocket(0).use { server ->
            val client = Socket()
            client.connect(InetSocketAddress("127.0.0.1", server.localPort), 2_000)
            server.accept().use { accepted ->
                tuneAcceptedSocket(accepted, 64 * 1024)
                assertTrue(accepted.tcpNoDelay)
                // Ядро часто округляет SO_SNDBUF вверх, но не оставляет его нулевым.
                assertTrue(accepted.sendBufferSize > 0)
            }
            client.close()
        }
    }
}
