package dev.zophop.data

import dev.zophop.domain.model.PreconditionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
