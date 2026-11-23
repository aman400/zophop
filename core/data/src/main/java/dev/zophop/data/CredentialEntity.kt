package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.zophop.domain.model.Credential
import org.json.JSONObject

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
            val json = JSONObject(dataJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = json.optString(k, "")
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
            val json = JSONObject()
            for ((k, v) in credential.dataMap) {
                json.put(k, v)
            }
            return CredentialEntity(
                id = credential.id,
                name = credential.name,
                type = credential.type,
                dataJson = json.toString(),
                createdAt = credential.createdAt,
                updatedAt = credential.updatedAt,
            )
        }
    }
}
