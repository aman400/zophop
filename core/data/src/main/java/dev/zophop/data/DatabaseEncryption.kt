package dev.zophop.data

import dev.zophop.domain.model.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.Security
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

    @Volatile
    private var cachedKeyStoreKey: SecretKey? = null

    @Volatile
    private var cachedTestKey: SecretKey? = null

    @Synchronized
    private fun getOrInitSecretKey(): SecretKey =
        if (Security.getProvider(ANDROID_KEYSTORE) != null) {
            cachedKeyStoreKey ?: getOrGenerateKeyStoreKey().also { cachedKeyStoreKey = it }
        } else {
            cachedTestKey ?: generateJvmTestKey().also { cachedTestKey = it }
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

    private fun generateJvmTestKey(): SecretKey {
        val seed = "zophop_secure_app_database_encryption_master_seed_2026".toByteArray(Charsets.UTF_8)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val keyBytes = sha256.digest(seed)
        return SecretKeySpec(keyBytes, "AES")
    }

    private val SENSITIVE_KEY_EXACT =
        setOf(
            "token",
            "apptoken",
            "app_token",
            "bottoken",
            "bot_token",
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "password",
            "passwd",
            "pwd",
            "passphrase",
            "secret",
            "clientsecret",
            "client_secret",
            "apikey",
            "api_key",
            "x-api-key",
            "x_api_key",
            "headers",
            "headersjson",
            "headers_json",
            "authheader",
            "auth_header",
            "authorization",
            "cookie",
            "privatekey",
            "private_key",
            "signingkey",
            "signing_key",
            "webhookurl",
            "webhook_url",
            "credential",
            "credentials",
        )

    fun isSensitiveKey(key: String): Boolean {
        val lower = key.trim().lowercase()
        return lower in SENSITIVE_KEY_EXACT ||
            lower.endsWith("token") ||
            lower.endsWith("secret") ||
            lower.endsWith("password") ||
            lower.endsWith("passphrase") ||
            lower.endsWith("apikey") ||
            lower.endsWith("api_key") ||
            lower.endsWith("privatekey") ||
            lower.endsWith("private_key") ||
            lower.endsWith("signingkey") ||
            lower.endsWith("signing_key")
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plainText: String): String {
        if (plainText.isBlank() || isEncrypted(plainText)) return plainText
        val secretKey = getOrInitSecretKey()

        val cipher = Cipher.getInstance(ALGORITHM)
        // AndroidKeyStore keys default to randomizedEncryptionRequired=true and forbid caller-provided IVs.
        // Letting the JCE/Keystore provider generate the GCM IV satisfies both AndroidKeyStore and JVM providers.
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_LENGTH) {
            "Expected $GCM_IV_LENGTH-byte GCM IV, got ${iv?.size}"
        }

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

        return PREFIX + Base64.getEncoder().encodeToString(combined)
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
            cipher.init(Cipher.DECRYPT_MODE, getOrInitSecretKey(), spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            cipherText
        } catch (_: IllegalArgumentException) {
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
            val root = AppJson.parseToJsonElement(configJson) as? JsonObject
            if (root == null) {
                configJson
            } else {
                val (transformedObj, modified) = transformJsonObject(root, transform)
                if (modified) transformedObj.toString() else configJson
            }
        } catch (_: Exception) {
            configJson
        }
    }

    private fun transformJsonObject(
        obj: JsonObject,
        transform: (String) -> String,
    ): Pair<JsonObject, Boolean> {
        var modified = false
        val newMap = obj.toMutableMap()
        for ((key, value) in obj) {
            val (updated, changed) = transformEntry(key, value, transform)
            if (changed) {
                newMap[key] = updated
                modified = true
            }
        }
        return JsonObject(newMap) to modified
    }

    private fun transformEntry(
        key: String,
        value: JsonElement,
        transform: (String) -> String,
    ): Pair<JsonElement, Boolean> =
        when (value) {
            is JsonPrimitive -> {
                if (isSensitiveKey(key) && value.isString) {
                    val transformed = transform(value.content)
                    if (transformed != value.content) JsonPrimitive(transformed) to true else value to false
                } else {
                    value to false
                }
            }
            is JsonObject -> transformJsonObject(value, transform)
            is JsonArray -> transformJsonArray(key, value, transform)
        }

    private fun transformJsonArray(
        parentKey: String,
        arr: JsonArray,
        transform: (String) -> String,
    ): Pair<JsonArray, Boolean> {
        var modified = false
        val newList =
            arr.map { elem ->
                val (updated, changed) = transformEntry(parentKey, elem, transform)
                if (changed) modified = true
                updated
            }
        return JsonArray(newList) to modified
    }
}
