package xyz.sabok.umtomi.sync.shared

import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.BadPaddingException
import org.json.JSONObject

/** Account-backed, not zero-knowledge: the Google account can access key records.
 * Immutable records and per-envelope IDs preserve simultaneous first connections. */
@androidx.annotation.RequiresApi(26)
internal class DriveAccountKeys {
    private val keys = sortedMapOf<String, String>()
    private val legacy = mutableSetOf<String>()
    val hasKeys: Boolean get() = keys.isNotEmpty()
    fun add(expectedId: String, text: String) {
        val record = JSONObject(text)
        require(record.getString("format") == "omyaru-account-key" && record.getInt("version") == 1 &&
            record.getString("id") == expectedId && Regex("^[0-9a-f]{32}$").matches(expectedId) && expectedId != "0".repeat(32))
        val secret = record.getString("secret")
        require(Base64.getDecoder().decode(secret).size == 32)
        require(keys[expectedId] == null || keys[expectedId] == secret) { "계정 키가 충돌합니다. 기존 데이터는 유지됩니다." }
        val old = if (!record.isNull("legacyEnvelope")) {
            JSONObject(decryptWith(record.getString("legacyEnvelope"), secret)).getString("passphrase").also { require(it.length >= 6) }
        } else null
        keys[expectedId] = secret
        old?.let { legacy.add(it) }
    }
    fun encrypt(plain: String): String {
        val key = keys.entries.first()
        return JSONObject(encryptWith(plain, key.value)).put("accountKeyId", key.key).toString()
    }
    fun decrypt(envelope: String): String {
        val value = JSONObject(envelope)
        if (value.has("accountKeyId")) {
            val secret = keys[value.getString("accountKeyId")]
                ?: error("데이터의 계정 키를 찾지 못했습니다. 다시 연결해 주세요. 기존 데이터는 유지됩니다.")
            return decryptWith(envelope, secret)
        }
        for (password in legacy) {
            try { return decryptWith(envelope, password) } catch (_: BadPaddingException) { }
        }
        error(MIGRATION_REQUIRED)
    }
    companion object {
        const val MIGRATION_REQUIRED = "이전 동기화 암호를 한 번 입력해 기존 데이터를 연결해 주세요."
        private fun encryptWith(plain: String, password: String): String {
            val bytes = plain.toByteArray(Charsets.UTF_8)
            val chars = password.toCharArray()
            return try { BrowserSyncCrypto.encrypt(bytes, chars) } finally { bytes.fill(0); chars.fill('\u0000') }
        }
        private fun decryptWith(envelope: String, password: String): String {
            val chars = password.toCharArray()
            val bytes = try { BrowserSyncCrypto.decrypt(envelope, chars) } finally { chars.fill('\u0000') }
            return try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        }
        fun record(id: String, secret: String, old: String?): String = JSONObject()
            .put("format", "omyaru-account-key").put("version", 1).put("id", id).put("secret", secret)
            .put("legacyEnvelope", old?.let { encryptWith(JSONObject().put("passphrase", it).toString(), secret) } ?: JSONObject.NULL).toString()
        fun load(client: DriveAppDataClient): DriveAccountKeys {
            val ring = DriveAccountKeys()
            val records = client.listKeys()
            check(records.size <= 128) { "계정 키 기록이 너무 많습니다. 기존 데이터는 유지됩니다." }
            for (record in records) ring.add(record.name.removePrefix(DriveAppDataClient.KEY_PREFIX).removeSuffix(".json"), client.read(record))
            return ring
        }
        fun connect(client: DriveAppDataClient, oldPassword: String): DriveAccountKeys {
            val ring = load(client)
            val files = client.list()
            check(files.size <= 5000)
            var needsLegacy = false
            for (file in files) {
                val envelope = client.read(file)
                try { ring.decrypt(envelope) }
                catch (error: IllegalStateException) {
                    if (error.message != MIGRATION_REQUIRED || oldPassword.length < 6) throw error
                    decryptWith(envelope, oldPassword)
                    needsLegacy = true
                }
            }
            if (ring.keys.isEmpty() || needsLegacy) {
                val id = UUID.randomUUID()
                val compactId = id.toString().replace("-", "")
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val secret = Base64.getEncoder().encodeToString(bytes)
                bytes.fill(0)
                val record = record(compactId, secret, if (needsLegacy) oldPassword else null)
                val fileId = client.appendKey(id, record)
                val stored = client.read(DriveAppDataClient.Entry(fileId, DriveAppDataClient.KEY_PREFIX + compactId + ".json", null))
                check(record == stored) { "계정 키 저장 검증에 실패했습니다. 기존 데이터는 유지됩니다." }
                ring.add(compactId, stored)
            }
            return ring
        }
        fun ensureKeys(client: DriveAppDataClient): DriveAccountKeys {
            val loaded = runCatching { load(client) }.getOrNull()
            if (loaded != null && loaded.hasKeys) return loaded
            return connect(client, "")
        }
    }
}
