package dev.zophop.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workflow_execution_logs")
data class WorkflowExecutionLogEntity(
    @PrimaryKey
    val id: String,
    val workflowId: String,
    val status: String, // SUCCESS, PRECONDITION_FAILED, FAILED, CANCELLED
    val timestamp: Long = System.currentTimeMillis(),
    val messageId: String? = null,
    val logs: String = "",
    val errorReason: String? = null,
)
