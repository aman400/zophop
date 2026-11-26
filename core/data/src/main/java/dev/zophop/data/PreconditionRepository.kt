package dev.zophop.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface PreconditionRepository {
    fun getAllPreconditionsFlow(): Flow<List<PreconditionEntity>>

    suspend fun getPreconditionById(id: String): PreconditionEntity?

    suspend fun savePrecondition(precondition: PreconditionEntity)

    suspend fun deletePrecondition(id: String)
}

class RoomPreconditionRepository(
    private val preconditionDao: PreconditionDao,
) : PreconditionRepository {
    override fun getAllPreconditionsFlow(): Flow<List<PreconditionEntity>> =
        preconditionDao.getAllPreconditionsFlow().map { list ->
            list.map { it.withDecryptedSecrets() }
        }

    override suspend fun getPreconditionById(id: String): PreconditionEntity? =
        preconditionDao.getPreconditionById(id)?.withDecryptedSecrets()

    override suspend fun savePrecondition(precondition: PreconditionEntity) {
        val updated = precondition.copy(updatedAt = System.currentTimeMillis()).withEncryptedSecrets()
        preconditionDao.insertOrUpdatePrecondition(updated)
    }

    override suspend fun deletePrecondition(id: String) {
        preconditionDao.deletePreconditionById(id)
    }
}
