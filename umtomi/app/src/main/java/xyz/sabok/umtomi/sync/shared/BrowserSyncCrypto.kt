package xyz.sabok.umtomi.sync.shared
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
@androidx.annotation.RequiresApi(26)
object BrowserSyncCrypto {
    private const val FORMAT = "privacy-browser-sync"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val aad = "$FORMAT-v$VERSION".toByteArray(StandardCharsets.UTF_8)
    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): String {
        require(passphrase.size >= 6) { "Passphrase must contain at least 6 characters" }
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", JSONObject()
                .put("name", "pbkdf2-sha256")
                .put("iterations", ITERATIONS)
                .put("salt", Base64.getEncoder().encodeToString(salt)))
            .put("cipher", JSONObject()
                .put("name", "aes-256-gcm")
                .put("nonce", Base64.getEncoder().encodeToString(nonce))
                .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext)))
            .toString()
    }

    fun decrypt(envelope: String, passphrase: CharArray): ByteArray {
        val document = JSONObject(envelope)
        require(document.optString("format") == FORMAT && document.optInt("version") == VERSION) {
            "Unsupported sync file"
        }
        val kdf = document.getJSONObject("kdf")
        val cipherDocument = document.getJSONObject("cipher")
        require(kdf.optString("name") == "pbkdf2-sha256") { "Unsupported key derivation" }
        require(cipherDocument.optString("name") == "aes-256-gcm") { "Unsupported cipher" }
        val iterations = kdf.optInt("iterations")
        require(iterations in 100_000..1_000_000) { "Unsafe key derivation settings" }
        val decoder = Base64.getDecoder()
        val salt = decoder.decode(kdf.getString("salt")).also { require(it.size == 16) }
        val nonce = decoder.decode(cipherDocument.getString("nonce")).also { require(it.size == 12) }
        val ciphertext = decoder.decode(cipherDocument.getString("ciphertext"))
        require(ciphertext.size in 16..MAX_ENCRYPTED_BYTES) { "Invalid encrypted payload size" }
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val specification = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            specification.clearPassword()
        }
    }

    private const val MAX_ENCRYPTED_BYTES = 32 * 1024 * 1024
}

