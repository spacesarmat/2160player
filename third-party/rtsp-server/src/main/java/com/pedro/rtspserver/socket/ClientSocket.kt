package com.pedro.rtspserver.socket

import com.pedro.common.socket.base.TcpStreamSocket

data class ClientSocket(
    val host: String,
    val port: Int,
    val socket: TcpStreamSocket,
    /** 2160 Player: локальный адрес, на который пришло подключение (по нему видно интерфейс). */
    val local: java.net.InetAddress? = null,
)