package dev.zophop.feature.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Utility for encrypting and decrypting sensitive strings (such as API tokens, passwords,
 * and authorization headers) using a user-specified PIN or password.
 *
 * Utilizes PBKDF2WithHmacSHA256 key derivation with 50,000 iterations and AES-256-GCM.
 */
object PasswordBasedEncryption {
    const val PREFIX = "enc:pw:v1:"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val SALT_LENGTH = 16
    private const val KEY_LENGTH_BITS = 256
    private const val ITERATIONS = 50_000

    private val secureRandom = SecureRandom()

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH_BITS)
        var keyBytes: ByteArray? = null
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val generated = factory.generateSecret(spec).encoded
            keyBytes = generated
            SecretKeySpec(generated, "AES")
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
            keyBytes?.fill(0)
        }
    }

    /**
     * Encrypts [plainText] using the provided [password].
     * Returns a formatted string: "enc:pw:v1:<saltBase64>:<ivBase64>:<cipherTextBase64>".
     */
    fun encrypt(plainText: String, password: CharSequence): String {
        if (plainText.isBlank()) return plainText
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val key = deriveKey(password.toString().toCharArray(), salt)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val saltB64 = Base64.getEncoder().encodeToString(salt)
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(cipherText)
        return "$PREFIX$saltB64:$ivB64:$ctB64"
    }

    /**
     * Decrypts an encrypted string that was produced by [encrypt].
     *
     * @throws SecurityException if password is incorrect or authentication tag verification fails.
     * @throws IllegalArgumentException if the encrypted payload format is invalid.
     */
    fun decrypt(encryptedString: String, password: CharSequence): String {
        if (!isEncryptedWithPassword(encryptedString)) return encryptedString
        val parts = encryptedString.removePrefix(PREFIX).split(":")
        require(parts.size == 3) { "Invalid encrypted payload format: expected salt:iv:ciphertext" }

        val (salt, iv, cipherText) = try {
            Triple(
                Base64.getDecoder().decode(parts[0]),
                Base64.getDecoder().decode(parts[1]),
                Base64.getDecoder().decode(parts[2]),
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed base64 in encrypted payload", e)
        }

        return try {
            val key = deriveKey(password.toString().toCharArray(), salt)
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val plainBytes = cipher.doFinal(cipherText)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw SecurityException("Incorrect PIN or password, or corrupted ciphertext", e)
        }
    }

    fun isEncryptedWithPassword(value: String): Boolean = value.startsWith(PREFIX)
}
