package dev.zophop.data

import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Test

class CredentialRepositoryTest {
    @Test
    fun testCredentialEntitySerialization() {
        val cred =
            Credential(
                id = "c1",
                name = "Test Bearer Credential",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to "secret-token-123"),
            )

        val entity = CredentialEntity.fromDomain(cred)
        assertEquals("c1", entity.id)
        assertEquals("Test Bearer Credential", entity.name)
        assertEquals("BEARER_TOKEN", entity.type)

        val restored = entity.toDomain()
        assertEquals("c1", restored.id)
        assertEquals(CredentialType.BEARER_TOKEN, restored.type)
        assertEquals("secret-token-123", restored.dataMap["token"])
    }
}
