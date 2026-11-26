package dev.zophop.plugin.api

import android.content.Context
import dev.zophop.domain.model.WorkflowNode

sealed class NodeExecutionResult {
    data class Success(
        val outputPort: String = "main",
        val logMessage: String = "Node executed successfully",
    ) : NodeExecutionResult()

    data class Failed(
        val reason: String,
    ) : NodeExecutionResult()

    data class Ignored(
        val reason: String = "Event filtered out by trigger rules",
    ) : NodeExecutionResult()
}

interface NodeExecutor {
    suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult
}
