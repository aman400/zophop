package dev.zophop.plugin.api

data class PluginExecutionResult(
    val isSuccess: Boolean,
    val outputPort: String = "main",
    val logMessage: String = "",
    val outputDataMap: Map<String, String> = emptyMap(),
    val errorReason: String? = null,
)

interface NodePluginExecutor {
    val manifest: NodePluginManifest

    suspend fun execute(
        nodeId: String,
        configJson: String,
        inputDataMap: Map<String, String>,
    ): PluginExecutionResult
}
