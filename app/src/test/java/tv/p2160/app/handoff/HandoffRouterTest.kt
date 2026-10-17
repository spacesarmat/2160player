package tv.p2160.app.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffRouterTest {
    @Test
    fun protectedRoutesRequireToken() {
        val routes = FakeRoutes(authorized = false)
        assertEquals(401, text(route("GET", "/now", routes = routes)).code)
        assertEquals(401, text(route("POST", "/play", body = "{}", routes = routes)).code)
        assertEquals(401, text(route("GET", "/camera", routes = routes)).code)
        val ok = text(route("GET", "/now", headers = mapOf("x-p2160-token" to "good"), routes = routes.copy(acceptToken = true)))
        assertEquals(200, ok.code)
        assertEquals("now", ok.body)
    }

    @Test
    fun streamNeedsKnownTokenAppDoesNot() {
        val routes = FakeRoutes()
        assertEquals(404, text(route("GET", "/stream/missing", routes = routes)).code)
        val stream = route("GET", "/stream/tok/movie.mkv", headers = mapOf("range" to "bytes=0-1"), routes = routes)
        assertTrue(stream is HandoffHttpResult.Stream)
        assertEquals("tok", (stream as HandoffHttpResult.Stream).token)
        assertEquals("bytes=0-1", stream.range)
        assertTrue(route("GET", "/app/player.apk", routes = routes) is HandoffHttpResult.Apk)
    }

    @Test
    fun watchGates() {
        val routes = FakeRoutes()
        routes.gate = WatchGate.NONE
        assertEquals(404, text(route("GET", "/watch", routes = routes)).code)
        routes.gate = WatchGate.BAD
        assertEquals(403, text(route("GET", "/watch", query = mapOf("k" to "nope"), routes = routes)).code)
        routes.gate = WatchGate.LOCKED
        assertEquals(429, text(route("GET", "/watch", routes = routes)).code)
        routes.gate = WatchGate.OK
        val page = text(route("GET", "/watch", query = mapOf("k" to "key"), routes = routes))
        assertEquals(200, page.code)
        assertEquals("text/html; charset=utf-8", page.type)
        assertEquals("watch", page.body)
    }

    @Test
    fun challengeLimitAndStatusIp() {
        val store = MemoryHandoffStore()
        HandoffAuth.initForTest(store)
        val routes = AuthRoutes()
        repeat(HandoffAuth.CHALLENGE_LIMIT) {
            val response = text(route("GET", "/pair/challenge", query = mapOf("id" to "c$it", "name" to "N"), remote = "10.2.0.5", routes = routes))
            assertEquals(200, response.code)
            assertTrue(response.body.contains("nonce"))
        }
        assertEquals(429, text(route("GET", "/pair/challenge", query = mapOf("id" to "c", "name" to "N"), remote = "10.2.0.5", routes = routes)).code)

        HandoffAuth.initForTest(store)
        val begun = HandoffAuth.beginChallenge("phone", "Phone", "10.2.0.5") as HandoffAuth.ChallengeBegin.Created
        assertTrue(HandoffAuth.approve(begun.nonce))
        val stranger = text(route("GET", "/pair/status", query = mapOf("nonce" to begun.nonce), remote = "10.9.9.9", routes = routes))
        assertEquals(403, stranger.code)
        assertFalse(routes.cancelled)
        val owner = text(route("GET", "/pair/status", query = mapOf("nonce" to begun.nonce), remote = "10.2.0.5", routes = routes))
        assertEquals(200, owner.code)
        assertTrue(owner.body.contains("token"))
        assertTrue(routes.cancelled)
    }

    @Test
    fun setupStyleHttpStillRejectsMissingAuthOnDescribeEquivalent() {
        val routes = FakeRoutes(authorized = false)
        assertEquals(401, text(route("GET", "/camera", routes = routes)).code)
    }

    @Test
    fun headersDroppedOnlyWhenProtectionOff() {
        val headers = mapOf("Authorization" to "Basic x", "Cookie" to "a=b", "User-Agent" to "VLC")
        assertEquals(setOf("User-Agent"), handoffHeaders(headers, protectionOn = false).keys)
        assertEquals(headers, handoffHeaders(headers, protectionOn = true))
    }

    @Test
    fun pairJsonAndBadJson() {
        assertEquals("phone", jsonString("""{"id":"phone","name":"A \"b\""}""", "id"))
        assertEquals("A \"b\"", jsonString("""{"id":"phone","name":"A \"b\""}""", "name"))
        assertEquals("", jsonString("""{"id":"phone"}""", "proof"))
        try {
            jsonString("not-json", "id")
            throw AssertionError("expected throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun route(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        remote: String = "192.168.1.2",
        routes: HandoffRoutes,
    ) = routeHandoff(method, path, query, headers, body, remote, routes)

    private fun text(result: HandoffHttpResult) = result as HandoffHttpResult.Text

    private class FakeRoutes(
        private val authorized: Boolean = true,
        val acceptToken: Boolean = false,
    ) : HandoffRoutes {
        var gate = WatchGate.OK
        override val deviceId = "self"
        override fun authorized(token: String?) = if (acceptToken) token == "good" else authorized && token == "good" || authorized && token == null && false
        override fun beginChallenge(id: String, name: String, remoteIp: String) = HandoffAuth.ChallengeBegin.Created("n")
        override fun showChallenge(nonce: String, name: String) = Unit
        override fun status(nonce: String, remoteIp: String) = null
        override fun cancelChallenge(nonce: String) = Unit
        override fun verify(id: String, name: String, nonce: String, proof: String) = HandoffAuth.PairResult.FAILED to null
        override fun onHello(id: String, name: String, port: Int, auth: Boolean, camera: Boolean, remoteHost: String) = Unit
        override fun watch(key: String?) = gate
        override fun watchBody(gate: WatchGate) = if (gate == WatchGate.OK) "watch" else "no"
        override fun cameraJson() = "{}"
        override fun nowJson() = "now"
        override fun onPlay(body: String) = Unit
        override fun hasStream(token: String) = token == "tok"

        fun copy(acceptToken: Boolean) = FakeRoutes(authorized, acceptToken)
    }

    private class AuthRoutes : HandoffRoutes {
        var cancelled = false
        override val deviceId = "self"
        override fun authorized(token: String?) = HandoffAuth.isAuthorized(token)
        override fun beginChallenge(id: String, name: String, remoteIp: String) = HandoffAuth.beginChallenge(id, name, remoteIp)
        override fun showChallenge(nonce: String, name: String) = Unit
        override fun status(nonce: String, remoteIp: String) = HandoffAuth.status(nonce, remoteIp)
        override fun cancelChallenge(nonce: String) { cancelled = true }
        override fun verify(id: String, name: String, nonce: String, proof: String) = HandoffAuth.verify(nonce, id, name, proof)
        override fun onHello(id: String, name: String, port: Int, auth: Boolean, camera: Boolean, remoteHost: String) = Unit
        override fun watch(key: String?) = WatchGate.NONE
        override fun watchBody(gate: WatchGate) = ""
        override fun cameraJson() = null
        override fun nowJson() = null
        override fun onPlay(body: String) = Unit
        override fun hasStream(token: String) = false
    }
}
