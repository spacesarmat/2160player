package tv.p2160.app.handoff

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Calendar
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Защита «Продолжить на другом устройстве» кодом, когда в одной сети несколько плееров.
 *
 * Сопряжение нужно один раз для нового устройства: клиент получает одноразовый nonce
 * (`GET /pair/challenge`), а хозяин видит уведомление «… хочет подключиться» с кнопками
 * «Разрешить/Отклонить» ([PairRequests]). Токен клиент получает либо по коду —
 * HMAC-SHA256(код, nonce:id) в `POST /pair`, — либо после «Разрешить», опрашивая `GET /pair/status`
 * с того же IP, что и challenge. Дальше запросы `/now` и `/play` идут с токеном, пока пользователь
 * не нажмёт «Забыть устройства». Код на сутки меняется в полночь — это касается только новых сопряжений.
 * Уже выданные токены (`issued_*`) от смены кода не пропадают.
 *
 * Пароль RTSP-трансляции и ключ QR `/watch` — другие секреты, не этот PIN.
 * Защищает от других плееров и людей в той же сети; от перехвата трафика в сети — нет (HTTP без TLS).
 */
object HandoffAuth {

    enum class Mode { OFF, DAILY, CUSTOM }

    /** Результат сопряжения. */
    enum class PairResult { OK, WRONG_CODE, LOCKED, FAILED }

    /** Ответ хозяина устройства на запрос подключения. */
    enum class Decision { PENDING, APPROVED, DENIED }

    /** Новый challenge: выдан, клиент недавно отклонён, или с этого IP слишком часто. */
    sealed interface ChallengeBegin {
        data class Created(val nonce: String) : ChallengeBegin
        data object Denied : ChallengeBegin
        data object Limited : ChallengeBegin
    }

    /** Состояние запроса. [sameIp] — опрашивает тот же адрес, что прислал challenge. Токен только тогда. */
    data class StatusResult(val decision: Decision, val token: String?, val sameIp: Boolean)

    /** Сколько ждём кода или ответа хозяина. */
    private const val CHALLENGE_TTL_MS = 3 * 60 * 1000L
    private const val DENY_MS = 10 * 60 * 1000L
    private const val MAX_FAILURES = 5
    private const val LOCK_MS = 60 * 1000L
    /** Не больше стольких challenge с одного IP за окно, иначе 429 и без уведомления. */
    internal const val CHALLENGE_LIMIT = 5
    private const val CHALLENGE_WINDOW_MS = 60 * 1000L

    private val random = SecureRandom()
    private var store: HandoffStore? = null

    private var failures = 0
    private var lockedUntil = 0L

    fun init(context: Context) {
        if (store == null) store = AndroidHandoffStore(context.applicationContext)
    }

    /** Подмена хранилища в unit-тестах. Сбрасывает запросы в памяти, не токены в [store]. */
    internal fun initForTest(store: HandoffStore) {
        this.store = store
        failures = 0
        lockedUntil = 0L
        pending.clear()
        deniedUntil.clear()
        challengeTimes.clear()
    }

    private fun s(): HandoffStore = store ?: error("HandoffAuth.init() not called")

    /** Постоянный идентификатор этого устройства (по нему другие плееры помнят сопряжение). */
    val deviceId: String
        get() = s().getString("device_id") ?: hex(8).also { s().putString("device_id", it) }

    var mode: Mode
        get() = runCatching { Mode.valueOf(s().getString("mode") ?: Mode.DAILY.name) }.getOrDefault(Mode.DAILY)
        set(value) = s().putString("mode", value.name)

    val required: Boolean get() = mode != Mode.OFF

    /** Пользователь уже подтвердил предупреждение о режиме «выключена». */
    var offWarningSeen: Boolean
        get() = s().getString("off_warned") == "1"
        set(value) = s().putString("off_warned", if (value) "1" else null)

    /** Свой код: новый — 6–8 цифр. Уже сохранённые 4–5 цифр по-прежнему принимаются. */
    var customCode: String
        get() = s().getString("custom_code").orEmpty()
        set(value) = s().putString("custom_code", value)

    /** Можно ли записать такой код как новый (6–8 цифр). */
    fun isValidCustomCode(code: String) = code.length in 6..8 && code.all(Char::isDigit)

    /** Можно ли оставить уже сохранённый код, включая старые 4–5 цифр. */
    fun isUsableCustomCode(code: String) = code.length in 4..8 && code.all(Char::isDigit)

    /** Действующий код: на сутки (6 цифр, меняется в полночь) или свой. */
    @Synchronized
    fun currentCode(): String = when (mode) {
        Mode.CUSTOM -> customCode.takeIf(::isUsableCustomCode).orEmpty()
        else -> {
            val today = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000L + it.get(Calendar.DAY_OF_YEAR) }
            val prefs = s()
            if (prefs.getLong("daily_day", -1) != today || prefs.getString("daily_code") == null) {
                val code = (random.nextInt(900_000) + 100_000).toString()
                prefs.putLong("daily_day", today)
                prefs.putString("daily_code", code)
            }
            prefs.getString("daily_code").orEmpty()
        }
    }

    // region Сервер: запросы на подключение и выданные токены

    /** Запрос на подключение: ждёт кода от клиента или решения хозяина (уведомление «Разрешить/Отклонить»). */
    private class Pending(val clientId: String, val clientName: String, val sourceIp: String, val createdAt: Long) {
        var decision = Decision.PENDING
        var token: String? = null
    }

    /** nonce → запрос (одноразовые, живут [CHALLENGE_TTL_MS]). */
    private val pending = HashMap<String, Pending>()
    /** clientId → до какого времени не беспокоить после «Отклонить». */
    private val deniedUntil = HashMap<String, Long>()
    /** IP → моменты challenge за последнее окно. */
    private val challengeTimes = HashMap<String, ArrayDeque<Long>>()

    /**
     * Новый запрос подключения от [clientId] с адреса [remoteIp].
     * [ChallengeBegin.Denied] — клиент недавно отклонён. [ChallengeBegin.Limited] — слишком часто с этого IP.
     */
    @Synchronized
    fun beginChallenge(clientId: String, clientName: String, remoteIp: String): ChallengeBegin {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.createdAt > CHALLENGE_TTL_MS }
        if (!allowChallenge(remoteIp, now)) return ChallengeBegin.Limited
        if ((deniedUntil[clientId] ?: 0L) > now) return ChallengeBegin.Denied
        val nonce = hex(16)
        pending[nonce] = Pending(clientId, clientName.take(64), remoteIp, now)
        return ChallengeBegin.Created(nonce)
    }

    private fun allowChallenge(remoteIp: String, now: Long): Boolean {
        val times = challengeTimes.getOrPut(remoteIp) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > CHALLENGE_WINDOW_MS) times.removeFirst()
        if (times.size >= CHALLENGE_LIMIT) return false
        times.addLast(now)
        return true
    }

    /** «Разрешить» в уведомлении: выдаём токен без кода. Забрать его можно только с IP challenge. */
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

    /**
     * Состояние запроса для опроса клиентом. Токен отдаётся один раз и только если [remoteIp]
     * совпадает с адресом challenge. Чужой IP запрос не закрывает.
     */
    @Synchronized
    fun status(nonce: String, remoteIp: String): StatusResult? {
        val req = pending[nonce] ?: return null
        if (System.currentTimeMillis() - req.createdAt > CHALLENGE_TTL_MS) {
            pending.remove(nonce)
            return null
        }
        if (remoteIp != req.sourceIp) return StatusResult(req.decision, null, sameIp = false)
        val token = req.token
        if (req.decision != Decision.PENDING) pending.remove(nonce)
        return StatusResult(req.decision, token, sameIp = true)
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
        s().putString("issued_$token", "$clientId|$clientName")
        return token
    }

    fun isAuthorized(token: String?): Boolean =
        !required || (token != null && token.length == 32 && s().contains("issued_$token"))

    /** Имена устройств, которым этот плеер выдал доступ. */
    fun trustedDevices(): List<String> =
        s().keys().filter { it.startsWith("issued_") }.mapNotNull { s().getString(it)?.substringAfter('|') }.distinct()

    // endregion

    // region Клиент: полученные токены

    fun tokenFor(peerId: String): String? = s().getString("peer_$peerId")

    fun saveToken(peerId: String, token: String) = s().putString("peer_$peerId", token)

    fun dropToken(peerId: String) = s().remove("peer_$peerId")

    // endregion

    /** Забыть все сопряжения: и выданные этим устройством, и полученные от других. */
    fun forgetAll() {
        s().keys().filter { it.startsWith("issued_") || it.startsWith("peer_") }.forEach { s().remove(it) }
    }

    fun proofFor(code: String, nonce: String, clientId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(code.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$nonce:$clientId".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}

/** Сравнение без раннего выхода по длине: для PIN, proof и ключа `/watch`. */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    val n = maxOf(a.length, b.length)
    var r = a.length xor b.length
    for (i in 0 until n) {
        val ca = if (i < a.length) a[i].code else 0
        val cb = if (i < b.length) b[i].code else 0
        r = r or (ca xor cb)
    }
    return r == 0
}

/** Минимальное хранилище настроек сопряжения: SharedPreferences на устройстве, память в тестах. */
internal interface HandoffStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    fun contains(key: String): Boolean
    fun keys(): Set<String>
    fun putString(key: String, value: String?)
    fun putLong(key: String, value: Long)
    fun remove(key: String)
}

internal class MemoryHandoffStore : HandoffStore {
    private val strings = HashMap<String, String>()
    private val longs = HashMap<String, Long>()

    override fun getString(key: String): String? = strings[key]
    override fun getLong(key: String, default: Long): Long = longs[key] ?: default
    override fun contains(key: String): Boolean = key in strings || key in longs
    override fun keys(): Set<String> = strings.keys + longs.keys
    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }
    override fun putLong(key: String, value: Long) { longs[key] = value }
    override fun remove(key: String) {
        strings.remove(key)
        longs.remove(key)
    }
}

private class AndroidHandoffStore(context: Context) : HandoffStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("p2160_handoff", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun keys(): Set<String> = prefs.all.keys
    override fun putString(key: String, value: String?) {
        prefs.edit().putString(key, value).apply()
    }
    override fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
