package dev.zophop.data

import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface WorkflowRepository {
    fun getAllWorkflowsFlow(): Flow<List<Workflow>>

    suspend fun getActiveWorkflows(): List<Workflow>

    suspend fun getWorkflowById(id: String): Workflow?

    suspend fun saveWorkflow(workflow: Workflow)

    suspend fun setWorkflowEnabled(
        id: String,
        isEnabled: Boolean,
    )

    suspend fun deleteWorkflow(id: String)

    suspend fun getWorkflowCount(): Int

    suspend fun saveExecutionLog(log: WorkflowExecutionLogEntity)

    suspend fun getExecutionLogById(id: String): WorkflowExecutionLogEntity?

    fun getRecentExecutionLogsFlow(limit: Int = 50): Flow<List<WorkflowExecutionLogEntity>>

    fun getLogsForWorkflowFlow(
        workflowId: String,
        limit: Int = 50,
    ): Flow<List<WorkflowExecutionLogEntity>>

    fun getQueuedWorkflowLogsFlow(): Flow<List<WorkflowExecutionLogEntity>>

    suspend fun getQueuedWorkflowLogs(): List<WorkflowExecutionLogEntity>

    suspend fun getRunningWorkflowLogs(): List<WorkflowExecutionLogEntity> = emptyList()

    suspend fun deleteExecutionLogById(id: String)

    suspend fun clearLogsForWorkflow(workflowId: String)
}

class RoomWorkflowRepository(
    private val workflowDao: WorkflowDao,
    private val preconditionDao: PreconditionDao? = null,
) : WorkflowRepository {
    private suspend fun syncWorkflow(workflow: Workflow): Workflow {
        val allPrecs = preconditionDao?.getAllPreconditions() ?: emptyList()
        if (allPrecs.isEmpty()) return workflow
        val precMap = allPrecs.associateBy { it.id }
        return workflow.copy(
            nodes =
                workflow.nodes.map { node ->
                    if (node.preconditions.isEmpty()) {
                        node
                    } else {
                        node.copy(
                            preconditions =
                                node.preconditions.map { prec ->
                                    val latestEntity = precMap[prec.id] ?: precMap.values.find { it.type == prec.type.name }
                                    latestEntity?.toDomain() ?: prec
                                },
                        )
                    }
                },
        )
    }

    override fun getAllWorkflowsFlow(): Flow<List<Workflow>> =
        workflowDao.getAllWorkflowsFlow().map { entities ->
            entities.map { it.toDomain() }
        }

    override suspend fun getActiveWorkflows(): List<Workflow> = workflowDao.getActiveWorkflows().map { syncWorkflow(it.toDomain()) }

    override suspend fun getWorkflowById(id: String): Workflow? = workflowDao.getWorkflowById(id)?.toDomain()?.let { syncWorkflow(it) }

    override suspend fun saveWorkflow(workflow: Workflow) {
        val updated = workflow.copy(updatedAt = System.currentTimeMillis())
        workflowDao.insertOrUpdateWorkflow(WorkflowEntity.fromDomain(updated))
    }

    override suspend fun setWorkflowEnabled(
        id: String,
        isEnabled: Boolean,
    ) {
        val workflow = getWorkflowById(id) ?: return
        saveWorkflow(workflow.copy(isEnabled = isEnabled))
    }

    override suspend fun deleteWorkflow(id: String) {
        workflowDao.deleteWorkflowById(id)
    }

    override suspend fun getWorkflowCount(): Int = workflowDao.getWorkflowCount()

    override suspend fun saveExecutionLog(log: WorkflowExecutionLogEntity) {
        workflowDao.insertExecutionLog(log)
    }

    override suspend fun getExecutionLogById(id: String): WorkflowExecutionLogEntity? =
        workflowDao.getExecutionLogById(id)

    override fun getRecentExecutionLogsFlow(limit: Int): Flow<List<WorkflowExecutionLogEntity>> =
        workflowDao.getRecentExecutionLogsFlow(limit)

    override fun getLogsForWorkflowFlow(
        workflowId: String,
        limit: Int,
    ): Flow<List<WorkflowExecutionLogEntity>> = workflowDao.getLogsForWorkflowFlow(workflowId, limit)

    override fun getQueuedWorkflowLogsFlow(): Flow<List<WorkflowExecutionLogEntity>> =
        workflowDao.getQueuedWorkflowLogsFlow()

    override suspend fun getQueuedWorkflowLogs(): List<WorkflowExecutionLogEntity> =
        workflowDao.getQueuedWorkflowLogs()

    override suspend fun getRunningWorkflowLogs(): List<WorkflowExecutionLogEntity> =
        workflowDao.getRunningWorkflowLogs()

    override suspend fun deleteExecutionLogById(id: String) {
        workflowDao.deleteExecutionLogById(id)
    }

    override suspend fun clearLogsForWorkflow(workflowId: String) {
        workflowDao.clearLogsForWorkflow(workflowId)
    }
}
