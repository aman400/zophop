package dev.zophop.data

import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialStoreAndMappingTest {
    private class FakeCredentialDao : CredentialDao {
        val storage = mutableMapOf<String, CredentialEntity>()

        override fun getAllCredentialsFlow() = flowOf(storage.values.toList())

        override suspend fun getCredentialById(id: String): CredentialEntity? = storage[id]

        override suspend fun insertOrUpdateCredential(credential: CredentialEntity) {
            storage[credential.id] = credential
        }

        override suspend fun deleteCredentialById(id: String) {
            storage.remove(id)
        }
    }

    @Test
    fun testBearerTokenCredentialEntityMapping() {
        val entity =
            CredentialEntity(
                id = "cred_bearer_99",
                name = "Stripe API Key",
                type = CredentialType.BEARER_TOKEN,
                dataJson = """{"token":"sk_test_51MzX9900221"}""",
                createdAt = 1000L,
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals("cred_bearer_99", domain.id)
        assertEquals("Stripe API Key", domain.name)
        assertEquals(CredentialType.BEARER_TOKEN, domain.type)
        assertEquals("sk_test_51MzX9900221", domain.dataMap["token"])

        val entityBack = CredentialEntity.fromDomain(domain)
        assertEquals(entity.id, entityBack.id)
        assertEquals(entity.name, entityBack.name)
        assertEquals(entity.type, entityBack.type)
    }

    @Test
    fun testBasicAuthCredentialEntityMapping() {
        val entity =
            CredentialEntity(
                id = "cred_basic_1",
                name = "Webhook Basic Auth",
                type = CredentialType.BASIC_AUTH,
                dataJson = """{"username":"admin","password":"secret_password_123"}""",
                createdAt = 1000L,
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals(CredentialType.BASIC_AUTH, domain.type)
        assertEquals("admin", domain.dataMap["username"])
        assertEquals("secret_password_123", domain.dataMap["password"])
    }

    @Test
    fun testNtfyAuthCredentialEntityMapping() {
        val entity =
            CredentialEntity(
                id = "cred_ntfy_1",
                name = "ntfy Server Vault",
                type = CredentialType.NTFY_AUTH,
                dataJson = """{"serverUrl":"https://ntfy.sh","topic":"my-secure-topic","token":"tk_secret_1"}""",
                createdAt = 1000L,
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals(CredentialType.NTFY_AUTH, domain.type)
        assertEquals("https://ntfy.sh", domain.dataMap["serverUrl"])
        assertEquals("my-secure-topic", domain.dataMap["topic"])
        assertEquals("tk_secret_1", domain.dataMap["token"])
    }

    @Test
    fun testGotifyAuthCredentialEntityMapping() {
        val entity =
            CredentialEntity(
                id = "cred_gotify_1",
                name = "Gotify Server Vault",
                type = CredentialType.GOTIFY_AUTH,
                dataJson = """{"serverUrl":"https://gotify.example.com","appToken":"A1B2C3D4E5"}""",
                createdAt = 1000L,
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals(CredentialType.GOTIFY_AUTH, domain.type)
        assertEquals("https://gotify.example.com", domain.dataMap["serverUrl"])
        assertEquals("A1B2C3D4E5", domain.dataMap["appToken"])
    }

    @Test
    fun testHeaderSetCredentialEntityMapping() {
        val entity =
            CredentialEntity(
                id = "cred_headers_1",
                name = "Custom Auth Headers",
                type = CredentialType.HEADER_SET,
                dataJson = """{"headers":"X-Api-Key: secret123\nX-Client-Id: client999"}""",
                createdAt = 1000L,
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals(CredentialType.HEADER_SET, domain.type)
        assertTrue(domain.dataMap["headers"]!!.contains("X-Api-Key: secret123"))
    }

    @Test
    fun testCorruptCredentialJsonDataMapFallback() {
        val entity =
            CredentialEntity(
                id = "cred_corrupt",
                name = "Corrupt Data",
                type = CredentialType.BEARER_TOKEN,
                dataJson = "INVALID_NON_JSON_STRING",
                createdAt = 1000L,
                updatedAt = 2000L,
            )

        val domain = entity.toDomain()
        assertEquals("cred_corrupt", domain.id)
        assertTrue(domain.dataMap.isEmpty())
    }

    @Test
    fun testCredentialTypeCompatibilityInterfaceDriven() {
        assertTrue(CredentialType.isCompatibleWith(CredentialType.NTFY_AUTH, "NTFY_AUTH"))
        assertTrue(CredentialType.isCompatibleWith(CredentialType.BEARER_TOKEN, "NTFY_AUTH"))
        assertTrue(CredentialType.isCompatibleWith(CredentialType.HEADER_SET, "NTFY_AUTH"))
        assertFalse(CredentialType.isCompatibleWith(CredentialType.GOTIFY_AUTH, "NTFY_AUTH"))

        assertTrue(CredentialType.isCompatibleWith(CredentialType.GOTIFY_AUTH, "GOTIFY_AUTH"))
        assertFalse(CredentialType.isCompatibleWith(CredentialType.BASIC_AUTH, "GOTIFY_AUTH"))

        // Any credential is compatible if no requirement is specified
        assertTrue(CredentialType.isCompatibleWith(CredentialType.BEARER_TOKEN, null))
        assertTrue(CredentialType.isCompatibleWith(CredentialType.BASIC_AUTH, ""))
    }

    @Test
    fun testCredentialRepositoryCrudOperations() =
        runTest {
            val fakeDao = FakeCredentialDao()
            val repository = RoomCredentialRepository(fakeDao)

            val cred =
                Credential(
                    id = "cred_repo_1",
                    name = "Repo Test Credential",
                    type = CredentialType.BEARER_TOKEN,
                    dataMap = mapOf("token" to "bearer_token_xyz"),
                )

            repository.saveCredential(cred)

            val fetched = repository.getCredentialById("cred_repo_1")
            assertNotNull(fetched)
            assertEquals("Repo Test Credential", fetched?.name)
            assertEquals("bearer_token_xyz", fetched?.dataMap?.get("token"))

            val allList = repository.getAllCredentialsFlow().first()
            assertEquals(1, allList.size)

            repository.deleteCredential("cred_repo_1")
            assertNull(repository.getCredentialById("cred_repo_1"))
            assertTrue(repository.getAllCredentialsFlow().first().isEmpty())
        }
}
