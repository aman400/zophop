package dev.zophop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC")
    fun getAllWorkflowsFlow(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows WHERE isEnabled = 1 ORDER BY updatedAt DESC")
    suspend fun getActiveWorkflows(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getWorkflowById(id: String): WorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateWorkflow(workflow: WorkflowEntity)

    @Query("DELETE FROM workflows WHERE id = :id")
    suspend fun deleteWorkflowById(id: String)

    @Query("SELECT COUNT(*) FROM workflows")
    suspend fun getWorkflowCount(): Int

    // Execution logs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecutionLog(log: WorkflowExecutionLogEntity)

    @Query("SELECT * FROM workflow_execution_logs WHERE id = :id")
    suspend fun getExecutionLogById(id: String): WorkflowExecutionLogEntity?

    @Query("SELECT * FROM workflow_execution_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentExecutionLogsFlow(limit: Int = 50): Flow<List<WorkflowExecutionLogEntity>>

    @Query("SELECT * FROM workflow_execution_logs WHERE workflowId = :workflowId ORDER BY timestamp DESC LIMIT :limit")
    fun getLogsForWorkflowFlow(
        workflowId: String,
        limit: Int = 50,
    ): Flow<List<WorkflowExecutionLogEntity>>

    @Query("SELECT * FROM workflow_execution_logs WHERE status = 'QUEUED' ORDER BY timestamp DESC")
    fun getQueuedWorkflowLogsFlow(): Flow<List<WorkflowExecutionLogEntity>>

    @Query("SELECT * FROM workflow_execution_logs WHERE status = 'QUEUED' ORDER BY timestamp DESC")
    suspend fun getQueuedWorkflowLogs(): List<WorkflowExecutionLogEntity>

    @Query("DELETE FROM workflow_execution_logs WHERE id = :id")
    suspend fun deleteExecutionLogById(id: String)

    @Query("DELETE FROM workflow_execution_logs WHERE workflowId = :workflowId")
    suspend fun clearLogsForWorkflow(workflowId: String)

    @Query("DELETE FROM workflow_execution_logs")
    suspend fun clearAllExecutionLogs()
}
