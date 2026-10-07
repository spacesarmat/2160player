package tv.p2160.app.handoff

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Защита «Продолжить на другом устройстве» кодом, когда в одной сети несколько плееров.
 *
 * Сопряжение нужно один раз для нового устройства: клиент получает одноразовый nonce
 * (`GET /pair/challenge`), а хозяин видит уведомление «… хочет подключиться» с кнопками
 * «Разрешить/Отклонить» ([PairRequests]). Токен клиент получает либо по коду —
 * HMAC-SHA256(код, nonce:id) в `POST /pair`, — либо после «Разрешить», опрашивая `GET /pair/status`.
 * Дальше запросы `/now` и `/play` идут с токеном, пока пользователь не нажмёт «Забыть устройства».
 * Код на сутки меняется в полночь — это касается только новых сопряжений.
 *
 * Защищает от других плееров и людей в той же сети; от перехвата трафика в сети — нет (HTTP без TLS).
 */
object HandoffAuth {

    enum class Mode { OFF, DAILY, CUSTOM }

    /** Результат сопряжения. */
    enum class PairResult { OK, WRONG_CODE, LOCKED, FAILED }

    /** Сколько ждём кода или ответа хозяина. */
    private const val CHALLENGE_TTL_MS = 3 * 60 * 1000L
    private const val DENY_MS = 10 * 60 * 1000L
    private const val MAX_FAILURES = 5
    private const val LOCK_MS = 60 * 1000L

    private val random = SecureRandom()
    private var prefs: SharedPreferences? = null

    private var failures = 0
    private var lockedUntil = 0L

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences("p2160_handoff", Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences = prefs ?: error("HandoffAuth.init() not called")

    /** Постоянный идентификатор этого устройства (по нему другие плееры помнят сопряжение). */
    val deviceId: String
        get() = p().getString("device_id", null) ?: hex(8).also { p().edit().putString("device_id", it).apply() }

    var mode: Mode
        get() = runCatching { Mode.valueOf(p().getString("mode", null) ?: Mode.DAILY.name) }.getOrDefault(Mode.DAILY)
        set(value) = p().edit().putString("mode", value.name).apply()

    val required: Boolean get() = mode != Mode.OFF

    /** Свой код: 4–8 цифр. */
    var customCode: String
        get() = p().getString("custom_code", "").orEmpty()
        set(value) = p().edit().putString("custom_code", value).apply()

    fun isValidCustomCode(code: String) = code.length in 4..8 && code.all(Char::isDigit)

    /** Действующий код: на сутки (6 цифр, меняется в полночь) или свой. */
    @Synchronized
    fun currentCode(): String = when (mode) {
        Mode.CUSTOM -> customCode
        else -> {
            val today = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }
            val prefs = p()
            if (prefs.getLong("daily_day", -1) != today || prefs.getString("daily_code", null) == null) {
                val code = (random.nextInt(900_000) + 100_000).toString()
                prefs.edit().putLong("daily_day", today).putString("daily_code", code).apply()
            }
            prefs.getString("daily_code", "").orEmpty()
        }
    }

    // region Сервер: запросы на подключение и выданные токены

    /** Ответ хозяина устройства на запрос подключения. */
    enum class Decision { PENDING, APPROVED, DENIED }

    /** Запрос на подключение: ждёт кода от клиента или решения хозяина (уведомление «Разрешить/Отклонить»). */
    private class Pending(val clientId: String, val clientName: String, val createdAt: Long) {
        var decision = Decision.PENDING
        var token: String? = null
    }

    /** nonce → запрос (одноразовые, живут [CHALLENGE_TTL_MS]). */
    private val pending = HashMap<String, Pending>()
    /** clientId → до какого времени не беспокоить после «Отклонить». */
    private val deniedUntil = HashMap<String, Long>()

    /**
     * Новый запрос подключения от [clientId]. null — клиент недавно отклонён: ни nonce, ни уведомления.
     */
    @Synchronized
    fun newChallenge(clientId: String, clientName: String): String? {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.createdAt > CHALLENGE_TTL_MS }
        if ((deniedUntil[clientId] ?: 0L) > now) return null
        return hex(16).also { pending[it] = Pending(clientId, clientName.take(64), now) }
    }

    /** «Разрешить» в уведомлении: выдаём токен без кода. */
    @Synchronized
    fun approve(nonce: String): Boolean {
        val req = pending[nonce]?.takeIf { it.decision == Decision.PENDING } ?: return false
        req.token = issue(req.clientId, req.clientName)
        req.decision = Decision.APPROVED
        return true
    }

    /** «Отклонить»: клиент получит отказ, повторные запросы от него 10 минут без уведомлений. */
    @Synchronized
    fun deny(nonce: String) {
        val req = pending[nonce] ?: return
        req.decision = Decision.DENIED
        deniedUntil[req.clientId] = System.currentTimeMillis() + DENY_MS
    }

    /** Состояние запроса для опроса клиентом; токен отдаётся один раз — после этого запрос закрыт. */
    @Synchronized
    fun status(nonce: String): Pair<Decision, String?>? {
        val req = pending[nonce] ?: return null
        if (System.currentTimeMillis() - req.createdAt > CHALLENGE_TTL_MS) { pending.remove(nonce); return null }
        if (req.decision != Decision.PENDING) pending.remove(nonce)
        return req.decision to req.token
    }

    /** Имя устройства, приславшего запрос (для уведомления). */
    @Synchronized
    fun requesterName(nonce: String): String? = pending[nonce]?.clientName

    /** Проверка кода от клиента; при успехе — новый токен для [clientId]. Неверный код запрос не закрывает. */
    @Synchronized
    fun verify(nonce: String, clientId: String, clientName: String, proof: String): Pair<PairResult, String?> {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PairResult.LOCKED to null
        val req = pending[nonce]
        if (req == null || req.clientId != clientId || now - req.createdAt > CHALLENGE_TTL_MS || req.decision == Decision.DENIED) {
            return PairResult.FAILED to null
        }
        val code = currentCode()
        if (code.isEmpty() || !constantTimeEquals(proof, proofFor(code, nonce, clientId))) {
            if (++failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = now + LOCK_MS
            }
            return PairResult.WRONG_CODE to null
        }
        failures = 0
        pending.remove(nonce)
        return PairResult.OK to issue(clientId, clientName.ifBlank { req.clientName })
    }

    private fun issue(clientId: String, clientName: String): String {
        val token = hex(16)
        p().edit().putString("issued_$token", "$clientId|$clientName").apply()
        return token
    }

    fun isAuthorized(token: String?): Boolean =
        !required || (token != null && token.length == 32 && p().contains("issued_$token"))

    /** Имена устройств, которым этот плеер выдал доступ. */
    fun trustedDevices(): List<String> =
        p().all.filterKeys { it.startsWith("issued_") }.values.map { it.toString().substringAfter('|') }.distinct()

    // endregion

    // region Клиент: полученные токены

    fun tokenFor(peerId: String): String? = p().getString("peer_$peerId", null)

    fun saveToken(peerId: String, token: String) = p().edit().putString("peer_$peerId", token).apply()

    fun dropToken(peerId: String) = p().edit().remove("peer_$peerId").apply()

    // endregion

    /** Забыть все сопряжения: и выданные этим устройством, и полученные от других. */
    fun forgetAll() {
        val edit = p().edit()
        p().all.keys.filter { it.startsWith("issued_") || it.startsWith("peer_") }.forEach(edit::remove)
        edit.apply()
    }

    fun proofFor(code: String, nonce: String, clientId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$nonce:$clientId".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
