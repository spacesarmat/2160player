package tv.p2160.core.secret

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Поля секретов в SharedPreferences: префикс [SECRET_PREFIX] и AES/GCM из Android Keystore.
 * Уже зашифрованное [seal] не трогает. Строка без префикса в [open] возвращается как есть,
 * чтобы сбой миграции не уничтожил пароль.
 */
object FieldSecrets {
    private const val TAG = "FieldSecrets"
    private const val ALIAS = "p2160.field"
    private const val STORE = "AndroidKeyStore"
    private const val IV_LEN = 12

    fun open(stored: String): String {
        if (!stored.startsWith(SECRET_PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(SECRET_PREFIX), Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return stored
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, IV_LEN)))
            String(cipher.doFinal(raw.copyOfRange(IV_LEN, raw.size)), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "open failed", it)
            stored
        }
    }

    fun seal(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(SECRET_PREFIX)) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val packed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            SECRET_PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        }.getOrElse {
            Log.w(TAG, "seal failed", it)
            plain
        }
    }

    /** При старте: пароли SMB и ключ RTMP в prefs переписать шифротекстом и стереть открытый текст. */
    fun migrate(context: Context) {
        val app = context.applicationContext
        runCatching { migrateSmb(app) }.onFailure { Log.w(TAG, "smb", it) }
        runCatching { migrateRtmp(app) }.onFailure { Log.w(TAG, "rtmp", it) }
    }

    private fun migrateSmb(context: Context) {
        val prefs = context.getSharedPreferences("p2160_smb", Context.MODE_PRIVATE)
        val raw = prefs.getString("servers", null) ?: return
        val next = migrateSmbJson(raw, ::seal)
        if (next != raw) prefs.edit().putString("servers", next).commit()
    }

    private fun migrateRtmp(context: Context) {
        val prefs = context.getSharedPreferences("p2160_camera", Context.MODE_PRIVATE)
        val raw = prefs.getString("rtmp_key", null) ?: return
        val next = migrateSecretField(raw, ::seal)
        if (next != raw) prefs.edit().putString("rtmp_key", next).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

internal const val SECRET_PREFIX = "enc1:"

/** Пустое и уже зашифрованное поле не меняется. */
internal fun migrateSecretField(value: String, seal: (String) -> String): String {
    if (value.isEmpty() || value.startsWith(SECRET_PREFIX)) return value
    return seal(value)
}

/**
 * В JSON списка SMB-серверов шифрует только непустые строковые поля `pass` без префикса.
 * Остальной текст не переписывается: так миграция не зависит от порядка ключей `JSONObject`.
 */
internal fun migrateSmbJson(json: String, seal: (String) -> String): String {
    val out = StringBuilder()
    var i = 0
    var changed = false
    while (i < json.length) {
        val valueStart = nextPassString(json, i) ?: break
        val parsed = readJsonString(json, valueStart)
        val next = migrateSecretField(parsed.value, seal)
        if (next == parsed.value) {
            out.append(json, i, parsed.end)
        } else {
            changed = true
            out.append(json, i, valueStart)
            out.append('"').append(escapeJson(next)).append('"')
        }
        i = parsed.end
    }
    if (!changed) return json
    out.append(json, i, json.length)
    return out.toString()
}

private data class JsonString(val value: String, val end: Int)

/** Индекс открывающей кавычки значения `"pass"`. */
private fun nextPassString(json: String, from: Int): Int? {
    var i = from
    while (i < json.length) {
        if (json[i] != '"') {
            i++
            continue
        }
        val key = readJsonString(json, i)
        i = key.end
        if (key.value != "pass") continue
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != ':') continue
        i++
        while (i < json.length && json[i].isWhitespace()) i++
        if (i < json.length && json[i] == '"') return i
    }
    return null
}

private fun readJsonString(json: String, start: Int): JsonString {
    if (json.getOrNull(start) != '"') throw IllegalArgumentException("json")
    var i = start + 1
    val sb = StringBuilder()
    while (i < json.length) {
        val c = json[i++]
        when (c) {
            '"' -> return JsonString(sb.toString(), i)
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

private fun escapeJson(value: String): String = buildString {
    for (c in value) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
