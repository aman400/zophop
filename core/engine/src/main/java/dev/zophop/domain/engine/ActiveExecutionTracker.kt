package dev.zophop.domain.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Data class representing the state of an actively or recently executing workflow.
 */
data class ActiveWorkflowExecution(
    val workflowId: String,
    val workflowName: String,
    val steps: List<NodeExecutionStep>,
    val isExecuting: Boolean,
    val result: WorkflowExecutionResult? = null,
)

/**
 * Global singleton tracker for workflow executions across all entry points
 * (foreground manual run, background SMS trigger, notification listener, queue worker).
 */
object ActiveExecutionTracker {
    private val _executions = MutableStateFlow<Map<String, ActiveWorkflowExecution>>(emptyMap())
    val executions: StateFlow<Map<String, ActiveWorkflowExecution>> = _executions.asStateFlow()

    fun updateExecution(
        workflowId: String,
        workflowName: String,
        steps: List<NodeExecutionStep>,
        isExecuting: Boolean,
        result: WorkflowExecutionResult? = null,
    ) {
        val updatedEntry = ActiveWorkflowExecution(
            workflowId = workflowId,
            workflowName = workflowName,
            steps = steps,
            isExecuting = isExecuting,
            result = result,
        )
        _executions.update { current -> current + (workflowId to updatedEntry) }
    }

    fun removeExecution(workflowId: String) {
        _executions.update { current ->
            if (current.containsKey(workflowId)) current - workflowId else current
        }
    }

    fun getExecution(workflowId: String): ActiveWorkflowExecution? = _executions.value[workflowId]

    fun clearAll() {
        _executions.value = emptyMap()
    }
}
