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
 * Код нужен один раз — для сопряжения нового устройства: клиент получает одноразовый nonce
 * (`GET /pair/challenge`), отвечает HMAC-SHA256(код, nonce:id) (`POST /pair`) и получает токен.
 * Дальше запросы `/now` и `/play` идут с токеном, пока пользователь не нажмёт «Забыть устройства».
 * Код на сутки меняется в полночь — это касается только новых сопряжений.
 *
 * Защищает от других плееров и людей в той же сети; от перехвата трафика в сети — нет (HTTP без TLS).
 */
object HandoffAuth {

    enum class Mode { OFF, DAILY, CUSTOM }

    /** Результат сопряжения. */
    enum class PairResult { OK, WRONG_CODE, LOCKED, FAILED }

    private const val CHALLENGE_TTL_MS = 2 * 60 * 1000L
    private const val MAX_FAILURES = 5
    private const val LOCK_MS = 60 * 1000L

    private val random = SecureRandom()
    private var prefs: SharedPreferences? = null

    /** nonce → когда выдан (одноразовые, живут 2 минуты). */
    private val challenges = HashMap<String, Long>()
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

    // region Сервер: выданные токены

    @Synchronized
    fun newChallenge(): String {
        val now = System.currentTimeMillis()
        challenges.entries.removeAll { now - it.value > CHALLENGE_TTL_MS }
        return hex(16).also { challenges[it] = now }
    }

    /** Проверка ответа клиента; при успехе — новый токен для [clientId]. */
    @Synchronized
    fun verify(nonce: String, clientId: String, clientName: String, proof: String): Pair<PairResult, String?> {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PairResult.LOCKED to null
        val issued = challenges.remove(nonce)
        if (issued == null || now - issued > CHALLENGE_TTL_MS) return PairResult.FAILED to null
        val code = currentCode()
        if (code.isEmpty() || !constantTimeEquals(proof, proofFor(code, nonce, clientId))) {
            if (++failures >= MAX_FAILURES) {
                failures = 0
                lockedUntil = now + LOCK_MS
            }
            return PairResult.WRONG_CODE to null
        }
        failures = 0
        val token = hex(16)
        p().edit().putString("issued_$token", "$clientId|$clientName").apply()
        return PairResult.OK to token
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
