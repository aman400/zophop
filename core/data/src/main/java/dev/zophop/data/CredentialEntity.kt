package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.parseJsonObjectSafe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive

@Entity(tableName = "credentials")
data class CredentialEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val dataJson: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toDomain(): Credential {
        val map = mutableMapOf<String, String>()
        try {
            val json = parseJsonObjectSafe(dataJson)
            for ((k, v) in json) {
                val rawVal = if (v is JsonPrimitive) v.content else v.toString()
                map[k] =
                    if (DatabaseEncryption.isSensitiveKey(k)) {
                        DatabaseEncryption.decrypt(rawVal)
                    } else {
                        rawVal
                    }
            }
        } catch (_: Exception) {
        }

        return Credential(
            id = id,
            name = name,
            type = type,
            dataMap = map,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        fun fromDomain(credential: Credential): CredentialEntity {
            val encryptedMap =
                credential.dataMap.mapValues { (k, v) ->
                    if (DatabaseEncryption.isSensitiveKey(k) && v.isNotBlank()) {
                        DatabaseEncryption.encrypt(v)
                    } else {
                        v
                    }
                }
            return CredentialEntity(
                id = credential.id,
                name = credential.name,
                type = credential.type,
                dataJson = AppJson.encodeToString(encryptedMap),
                createdAt = credential.createdAt,
                updatedAt = credential.updatedAt,
            )
        }
    }
}
