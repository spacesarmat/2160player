package tv.p2160.app.handoff

import java.net.URLDecoder

/**
 * Разбор одного запроса сервера передачи без сокета. Сокетная обвязка - в [Handoff].
 * Заголовки уже в нижнем регистре (так их собирает сервер).
 */
internal enum class WatchGate { OK, BAD, LOCKED, NONE }

internal interface HandoffRoutes {
    val deviceId: String
    fun authorized(token: String?): Boolean
    fun beginChallenge(id: String, name: String, remoteIp: String): HandoffAuth.ChallengeBegin
    fun showChallenge(nonce: String, name: String)
    fun status(nonce: String, remoteIp: String): HandoffAuth.StatusResult?
    fun cancelChallenge(nonce: String)
    fun verify(id: String, name: String, nonce: String, proof: String): Pair<HandoffAuth.PairResult, String?>
    fun onHello(id: String, name: String, port: Int, auth: Boolean, camera: Boolean, remoteHost: String)
    fun watch(key: String?): WatchGate
    fun watchBody(gate: WatchGate): String
    fun cameraJson(): String?
    fun nowJson(): String?
    fun onPlay(body: String)
    fun hasStream(token: String): Boolean
}

internal sealed class HandoffHttpResult {
    data class Text(val code: Int, val body: String = "", val type: String = "text/plain") : HandoffHttpResult()
    data class Stream(val token: String, val range: String?) : HandoffHttpResult()
    data class Apk(val range: String?) : HandoffHttpResult()
}

internal fun routeHandoff(
    method: String,
    path: String,
    query: Map<String, String>,
    headers: Map<String, String>,
    body: String,
    remoteIp: String?,
    routes: HandoffRoutes,
): HandoffHttpResult {
    val ip = remoteIp.orEmpty()
    if ((path == "/now" || path == "/play" || path == "/camera") && !routes.authorized(headers["x-p2160-token"])) {
        return HandoffHttpResult.Text(401)
    }
    return when {
        method == "GET" && path == "/pair/challenge" -> when (
            val begun = routes.beginChallenge(query["id"].orEmpty(), query["name"].orEmpty().ifBlank { "?" }.take(64), ip)
        ) {
            is HandoffAuth.ChallengeBegin.Created -> {
                routes.showChallenge(begun.nonce, query["name"].orEmpty().ifBlank { "?" }.take(64))
                HandoffHttpResult.Text(200, """{"nonce":"${begun.nonce}"}""", "application/json")
            }
            HandoffAuth.ChallengeBegin.Denied -> HandoffHttpResult.Text(403)
            HandoffAuth.ChallengeBegin.Limited -> HandoffHttpResult.Text(429)
        }
        method == "GET" && path == "/pair/status" -> {
            val nonce = query["nonce"].orEmpty()
            val status = routes.status(nonce, ip) ?: return HandoffHttpResult.Text(404)
            if (!status.sameIp) return HandoffHttpResult.Text(403)
            if (status.decision != HandoffAuth.Decision.PENDING) routes.cancelChallenge(nonce)
            val state = status.decision.name.lowercase()
            val json = if (status.token != null) """{"state":"$state","token":"${status.token}"}""" else """{"state":"$state"}"""
            HandoffHttpResult.Text(200, json, "application/json")
        }
        method == "POST" && path == "/pair" -> {
            val id = jsonString(body, "id")
            val name = jsonString(body, "name").take(64)
            val nonce = jsonString(body, "nonce")
            val proof = jsonString(body, "proof")
            val (result, token) = routes.verify(id, name, nonce, proof)
            when (result) {
                HandoffAuth.PairResult.OK -> {
                    routes.cancelChallenge(nonce)
                    HandoffHttpResult.Text(200, """{"token":"$token"}""", "application/json")
                }
                HandoffAuth.PairResult.WRONG_CODE -> HandoffHttpResult.Text(403)
                HandoffAuth.PairResult.LOCKED -> HandoffHttpResult.Text(429)
                HandoffAuth.PairResult.FAILED -> HandoffHttpResult.Text(400)
            }
        }
        method == "GET" && path == "/hello" -> {
            val id = query["id"].orEmpty()
            val port = query["port"]?.toIntOrNull()
            if (id.isBlank() || id == routes.deviceId || port == null || remoteIp == null) {
                HandoffHttpResult.Text(400)
            } else {
                routes.onHello(id, query["name"].orEmpty().ifBlank { "?" }.take(64), port, query["auth"] == "1", query["cam"] == "1", remoteIp)
                HandoffHttpResult.Text(204)
            }
        }
        method == "GET" && path == "/watch" -> {
            val gate = routes.watch(query["k"])
            val type = "text/html; charset=utf-8"
            when (gate) {
                WatchGate.NONE -> HandoffHttpResult.Text(404, routes.watchBody(gate), type)
                WatchGate.LOCKED -> HandoffHttpResult.Text(429, routes.watchBody(gate), type)
                WatchGate.BAD -> HandoffHttpResult.Text(403, routes.watchBody(gate), type)
                WatchGate.OK -> HandoffHttpResult.Text(200, routes.watchBody(gate), type)
            }
        }
        method == "GET" && path == "/camera" -> {
            val json = routes.cameraJson()
            if (json == null) HandoffHttpResult.Text(204) else HandoffHttpResult.Text(200, json, "application/json")
        }
        method == "GET" && path == "/now" -> {
            val json = routes.nowJson()
            if (json == null) HandoffHttpResult.Text(204) else HandoffHttpResult.Text(200, json, "application/json")
        }
        method == "POST" && path == "/play" -> {
            routes.onPlay(body)
            HandoffHttpResult.Text(200, "{}", "application/json")
        }
        method == "GET" && path.startsWith("/app") -> HandoffHttpResult.Apk(headers["range"])
        method == "GET" && path.startsWith("/stream/") -> {
            val token = path.removePrefix("/stream/").substringBefore('/')
            if (!routes.hasStream(token)) HandoffHttpResult.Text(404)
            else HandoffHttpResult.Stream(token, headers["range"])
        }
        else -> HandoffHttpResult.Text(404)
    }
}

/** Заголовки чужого воспроизведения. В режиме «защита выключена» Authorization и Cookie не отдаём. */
internal fun handoffHeaders(headers: Map<String, String>, protectionOn: Boolean): Map<String, String> {
    if (protectionOn) return headers
    return headers.filterKeys {
        val name = it.lowercase()
        name != "authorization" && name != "cookie"
    }
}

internal fun decodeQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split('&')) {
        if (part.isEmpty()) continue
        val i = part.indexOf('=')
        val key = urlDecode(if (i < 0) part else part.substring(0, i))
        val value = urlDecode(if (i < 0) "" else part.substring(i + 1))
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}

private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)

/**
 * Поле-строка из небольшого JSON-объекта. Нет поля - пустая строка.
 * Битый JSON - исключение, как раньше у `JSONObject` на сокете.
 */
internal fun jsonString(json: String, key: String): String {
    var i = 0
    fun skipWs() {
        while (i < json.length && json[i].isWhitespace()) i++
    }
    fun parseString(): String {
        if (i >= json.length || json[i] != '"') throw IllegalArgumentException("json")
        i++
        val sb = StringBuilder()
        while (i < json.length) {
            val c = json[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= json.length) throw IllegalArgumentException("json")
                    when (val e = json[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > json.length) throw IllegalArgumentException("json")
                            sb.append(json.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("json")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw IllegalArgumentException("json")
    }
    fun skipValue() {
        skipWs()
        if (i >= json.length) throw IllegalArgumentException("json")
        when (json[i]) {
            '"' -> parseString()
            '{', '[' -> {
                val open = json[i]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                var inStr = false
                var esc = false
                while (i < json.length) {
                    val c = json[i++]
                    if (inStr) {
                        if (esc) esc = false
                        else if (c == '\\') esc = true
                        else if (c == '"') inStr = false
                        continue
                    }
                    when (c) {
                        '"' -> inStr = true
                        open -> depth++
                        close -> {
                            depth--
                            if (depth == 0) return
                        }
                    }
                }
                throw IllegalArgumentException("json")
            }
            else -> while (i < json.length && json[i] != ',' && json[i] != '}' && json[i] != ']') i++
        }
    }
    skipWs()
    if (i >= json.length || json[i] != '{') throw IllegalArgumentException("json")
    i++
    var found: String? = null
    while (true) {
        skipWs()
        if (i < json.length && json[i] == '}') {
            i++
            break
        }
        val name = parseString()
        skipWs()
        if (i >= json.length || json[i] != ':') throw IllegalArgumentException("json")
        i++
        skipWs()
        if (i < json.length && json[i] == '"') {
            val value = parseString()
            if (name == key && found == null) found = value
        } else {
            skipValue()
        }
        skipWs()
        if (i < json.length && json[i] == ',') {
            i++
            continue
        }
        skipWs()
        if (i < json.length && json[i] == '}') {
            i++
            break
        }
        throw IllegalArgumentException("json")
    }
    skipWs()
    if (i != json.length) throw IllegalArgumentException("json")
    return found.orEmpty()
}
