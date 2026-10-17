package com.pedro.rtspserver.server

import com.pedro.rtsp.rtsp.commands.Method
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class RtspAuthTest {
    @Test
    fun describeAndSetupWithoutAuthorizationAre401() {
        val manager = manager()
        val describe = manager.createResponse(Method.DESCRIBE, "DESCRIBE rtsp://192.168.1.2:8554/ RTSP/1.0\r\nCSeq: 1\r\n", 1, "192.168.1.9")
        assertTrue(describe.startsWith("RTSP/1.0 401"))
        assertTrue(describe.contains("WWW-Authenticate: Basic"))
        val setup = manager.createResponse(Method.SETUP, setup(null), 2, "192.168.1.9")
        assertTrue(setup.startsWith("RTSP/1.0 401"))
    }

    @Test
    fun wrongPasswordIs401() {
        val setup = manager().createResponse(Method.SETUP, setup(Base64.encode("2160:nope".toByteArray())), 2, "192.168.1.9")
        assertTrue(setup.startsWith("RTSP/1.0 401"))
    }

    @Test
    fun playPauseTeardownNeedSetupOnTheSameConnection() {
        val manager = manager()
        val auth = Base64.encode("2160:secret".toByteArray())
        assertTrue(manager.createResponse(Method.PLAY, "PLAY rtsp://x RTSP/1.0\r\nAuthorization: Basic $auth\r\n", 3, "192.168.1.9").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.PAUSE, "PAUSE rtsp://x RTSP/1.0\r\n", 4, "192.168.1.9").startsWith("RTSP/1.0 401"))
        assertTrue(manager.createResponse(Method.TEARDOWN, "TEARDOWN rtsp://x RTSP/1.0\r\n", 5, "192.168.1.9").startsWith("RTSP/1.0 401"))
        val setup = manager.createResponse(Method.SETUP, setup(auth), 2, "192.168.1.9")
        assertTrue(setup.startsWith("RTSP/1.0 200"))
        assertTrue(setup.contains("interleaved="))
        val play = manager.createResponse(Method.PLAY, "PLAY rtsp://x RTSP/1.0\r\n", 6, "192.168.1.9")
        assertTrue(play.startsWith("RTSP/1.0 200"))
        assertFalse(play.contains("RTP-Info"))
        val other = manager()
        assertTrue(other.createResponse(Method.PLAY, "PLAY rtsp://x RTSP/1.0\r\nAuthorization: Basic $auth\r\n", 1, "192.168.1.9").startsWith("RTSP/1.0 401"))
    }

    @Test
    fun protectionOffStaysPasswordlessButStillRequiresSetup() {
        val manager = manager(user = null, password = null)
        assertTrue(manager.createResponse(Method.PLAY, "PLAY rtsp://x RTSP/1.0\r\n", 1, "10.0.0.2").startsWith("RTSP/1.0 401"))
        val setup = manager.createResponse(Method.SETUP, setup(null), 2, "10.0.0.2")
        assertTrue(setup.startsWith("RTSP/1.0 200"))
        assertTrue(manager.createResponse(Method.PLAY, "PLAY rtsp://x RTSP/1.0\r\n", 3, "10.0.0.2").startsWith("RTSP/1.0 200"))
    }

    private fun manager(user: String? = "2160", password: String? = "secret") = ServerCommandManager().apply {
        setAuth(user, password)
        setServerInfo("192.168.1.2", 8554)
    }

    private fun setup(basic: String?): String = buildString {
        append("SETUP rtsp://192.168.1.2:8554/ RTSP/1.0\r\n")
        append("CSeq: 2\r\n")
        append("Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n")
        append("streamid=0\r\n")
        if (basic != null) append("Authorization: Basic $basic\r\n")
        append("\r\n")
    }
}
