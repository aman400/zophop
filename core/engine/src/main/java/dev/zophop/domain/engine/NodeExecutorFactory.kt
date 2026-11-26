package dev.zophop.domain.engine

import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.engine.plugin.ExternalPluginExecutor
import dev.zophop.domain.engine.plugin.ExternalPluginRegistry
import dev.zophop.plugin.api.NodeExecutor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

open class NodeExecutorFactory(
    private val okHttpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build(),
    private val credentialRepository: CredentialRepository? = null,
    private val pluginRegistry: ExternalPluginRegistry? = null,
) {
    open fun getExecutor(type: String): NodeExecutor {
        val moduleExecutor = BuiltInPluginRegistry.createExecutor(type, okHttpClient, credentialRepository)
        if (moduleExecutor != null) return moduleExecutor

        val externalExecutor = getCustomExecutorByTypeId(type)
        if (externalExecutor != null) return externalExecutor

        throw IllegalArgumentException("Unknown node type '$type' (required plugin is not installed or is disabled)")
    }

    open fun getCustomExecutorByTypeId(typeId: String): NodeExecutor? {
        val pluginInfo = pluginRegistry?.getPluginByTypeId(typeId) ?: return null
        return ExternalPluginExecutor(pluginInfo, credentialRepository)
    }
}
