package dev.zophop.data

import dev.zophop.domain.model.PreconditionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionRepositoryTest {
    private class FakePreconditionDao : PreconditionDao {
        val map = mutableMapOf<String, PreconditionEntity>()

        override fun getAllPreconditionsFlow() = flowOf(map.values.toList())

        override suspend fun getAllPreconditions(): List<PreconditionEntity> = map.values.toList()

        override suspend fun getPreconditionById(id: String): PreconditionEntity? = map[id]

        override suspend fun insertOrUpdatePrecondition(precondition: PreconditionEntity) {
            map[precondition.id] = precondition
        }

        override suspend fun deletePreconditionById(id: String) {
            map.remove(id)
        }
    }

    @Test
    fun testPreconditionCrudOperations() =
        runTest {
            val fakeDao = FakePreconditionDao()
            val repo = RoomPreconditionRepository(fakeDao)

            val entity =
                PreconditionEntity(
                    id = "prec_health",
                    name = "Health Check Server",
                    type = PreconditionType.HEALTH_CHECK.name,
                    isEnabled = true,
                    targetUrl = "https://ntfy.sh/v1/health",
                    expectedStatus = 200,
                    onFailureAction = "RETRY",
                    maxRetries = 4,
                    retryIntervalSeconds = 15,
                )

            repo.savePrecondition(entity)

            val fetched = repo.getPreconditionById("prec_health")
            assertNotNull(fetched)
            assertEquals("Health Check Server", fetched?.name)
            assertEquals("https://ntfy.sh/v1/health", fetched?.targetUrl)
            assertEquals(200, fetched?.expectedStatus)
            assertTrue(fetched?.isEnabled == true)

            val allList = repo.getAllPreconditionsFlow().first()
            assertEquals(1, allList.size)

            repo.deletePrecondition("prec_health")
            assertNull(repo.getPreconditionById("prec_health"))
            assertTrue(repo.getAllPreconditionsFlow().first().isEmpty())
        }

    @Test
    fun testPreconditionSecretsEncryptedAtRestInDaoAndDecryptedOnRead() =
        runTest {
            val fakeDao = FakePreconditionDao()
            val repo = RoomPreconditionRepository(fakeDao)

            val pipelineJson =
                dev.zophop.domain.model.AppJson.encodeToString(
                    dev.zophop.domain.model.WorkflowGraph(
                        nodes =
                            listOf(
                                dev.zophop.domain.model.WorkflowNode(
                                    id = "cn1",
                                    name = "Token Step",
                                    type = "HTTP_ACTION",
                                    configJson = """{"url":"https://auth.example.com","bearerToken":"chain_secret_888"}""",
                                ),
                            ),
                    ),
                )

            val entity =
                PreconditionEntity(
                    id = "prec_secret_hc",
                    name = "Secured Check",
                    type = PreconditionType.HEALTH_CHECK.name,
                    targetUrl = "https://api.example.com/health",
                    headers = "Authorization: Bearer health_header_secret_777",
                    pipelineJson = pipelineJson,
                )

            repo.savePrecondition(entity)

            val storedRaw = fakeDao.map["prec_secret_hc"]
            assertNotNull(storedRaw)
            assertFalse(
                "Stored headers in DAO must be encrypted",
                storedRaw!!.headers.contains("health_header_secret_777"),
            )
            assertTrue(
                "Stored headers in DAO must use enc:v1: prefix",
                DatabaseEncryption.isEncrypted(storedRaw.headers),
            )
            assertFalse(
                "Stored pipelineJson in DAO must not expose plaintext bearerToken",
                storedRaw.pipelineJson.contains("chain_secret_888"),
            )

            val fetched = repo.getPreconditionById("prec_secret_hc")
            assertEquals("Authorization: Bearer health_header_secret_777", fetched?.headers)
            assertTrue(fetched?.pipelineJson?.contains("chain_secret_888") == true)

            val flowFetched = repo.getAllPreconditionsFlow().first().single()
            assertEquals("Authorization: Bearer health_header_secret_777", flowFetched.headers)
        }
}
