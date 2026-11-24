package dev.zophop.data

import dev.zophop.domain.model.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object DatabaseEncryption {
    private const val PREFIX = "enc:v1:"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12
    private const val KEY_ALIAS = "zophop_db_encryption_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private val secureRandom = SecureRandom()

    private val secretKey: SecretKey by lazy {
        initOrGetSecretKey()
    }

    private fun initOrGetSecretKey(): SecretKey =
        try {
            getOrGenerateKeyStoreKey()
        } catch (_: Throwable) {
            generateFallbackKey()
        }

    private fun getOrGenerateKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }
        val keyGenerator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val builderClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val keyPropertiesClass = Class.forName("android.security.keystore.KeyProperties")

        val purposeField = keyPropertiesClass.getField("PURPOSE_ENCRYPT").getInt(null) or
            keyPropertiesClass.getField("PURPOSE_DECRYPT").getInt(null)
        val blockModeGcm = keyPropertiesClass.getField("BLOCK_MODE_GCM").get(null) as String
        val paddingNone = keyPropertiesClass.getField("ENCRYPTION_PADDING_NONE").get(null) as String

        val constructor = builderClass.getConstructor(String::class.java, Int::class.javaPrimitiveType)
        val builder = constructor.newInstance(KEY_ALIAS, purposeField)

        builderClass.getMethod("setBlockModes", Array<String>::class.java)
            .invoke(builder, arrayOf(blockModeGcm))
        builderClass.getMethod("setEncryptionPaddings", Array<String>::class.java)
            .invoke(builder, arrayOf(paddingNone))
        builderClass.getMethod("setKeySize", Int::class.javaPrimitiveType)
            .invoke(builder, 256)

        val spec = builderClass.getMethod("build").invoke(builder)
        keyGenerator.javaClass.getMethod("init", java.security.spec.AlgorithmParameterSpec::class.java)
            .invoke(keyGenerator, spec)

        return keyGenerator.generateKey()
    }

    private fun generateFallbackKey(): SecretKey {
        val seed = "zophop_secure_app_database_encryption_master_seed_2026".toByteArray(Charsets.UTF_8)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = sha256.digest(seed)
        return SecretKeySpec(keyBytes, "AES")
    }

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower == "token" ||
            lower == "apptoken" ||
            lower == "bottoken" ||
            lower == "password" ||
            lower == "secret" ||
            lower == "apikey" ||
            lower == "api_key" ||
            lower == "headers" ||
            lower.endsWith("token") ||
            lower.endsWith("secret") ||
            lower.endsWith("password")
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plainText: String): String {
        if (plainText.isBlank() || isEncrypted(plainText)) return plainText
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            PREFIX + Base64.getEncoder().encodeToString(combined)
        } catch (_: Exception) {
            plainText
        }
    }

    fun decrypt(cipherText: String): String {
        if (!isEncrypted(cipherText)) return cipherText
        return try {
            val raw = cipherText.removePrefix(PREFIX)
            val combined = Base64.getDecoder().decode(raw)
            if (combined.size <= GCM_IV_LENGTH) return cipherText

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encryptedBytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            cipherText
        }
    }

    fun encryptNodeConfig(configJson: String): String =
        transformSensitiveKeys(configJson) { value ->
            if (value.isNotBlank() && !isEncrypted(value)) encrypt(value) else value
        }

    fun decryptNodeConfig(configJson: String): String =
        transformSensitiveKeys(configJson) { value ->
            if (isEncrypted(value)) decrypt(value) else value
        }

    private fun transformSensitiveKeys(configJson: String, transform: (String) -> String): String {
        if (configJson.isBlank()) return configJson
        return try {
            val root = AppJson.parseToJsonElement(configJson) as? JsonObject ?: return configJson
            var modified = false
            val newMap = root.toMutableMap()
            for ((key, value) in root) {
                if (!isSensitiveKey(key)) continue
                val strVal = if (value is JsonPrimitive) value.content else continue
                val transformed = transform(strVal)
                if (transformed != strVal) {
                    newMap[key] = JsonPrimitive(transformed)
                    modified = true
                }
            }
            if (modified) JsonObject(newMap).toString() else configJson
        } catch (_: Exception) {
            configJson
        }
    }
}
