package dev.zophop.domain.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.UUID

object CredentialType {
    const val BEARER_TOKEN = "BEARER_TOKEN"
    const val BASIC_AUTH = "BASIC_AUTH"
    const val HEADER_SET = "HEADER_SET"
    const val NTFY_AUTH = "NTFY_AUTH"
    const val GOTIFY_AUTH = "GOTIFY_AUTH"

    fun isCompatibleWith(
        credentialType: String,
        requiredCredentialType: String?,
    ): Boolean {
        if (requiredCredentialType.isNullOrBlank()) return true
        return when (requiredCredentialType) {
            NTFY_AUTH -> credentialType == NTFY_AUTH || credentialType == BEARER_TOKEN || credentialType == HEADER_SET
            GOTIFY_AUTH -> credentialType == GOTIFY_AUTH
            BEARER_TOKEN -> credentialType == BEARER_TOKEN || credentialType == HEADER_SET
            BASIC_AUTH -> credentialType == BASIC_AUTH || credentialType == HEADER_SET
            HEADER_SET -> credentialType == HEADER_SET
            else -> credentialType.equals(requiredCredentialType, ignoreCase = true) || credentialType == BEARER_TOKEN
        }
    }
}

@Serializable
data class Credential(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("data", "dataJson")
    val dataMap: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
