package dev.zophop.domain.engine.plugin

import android.content.Context
import dev.zophop.plugin.api.CredentialSpec

open class CredentialSpecRegistry(
    private val context: Context?,
) {
    private val unifiedPluginRegistry = UnifiedPluginRegistry(context)

    open suspend fun getAllCredentialSpecs(): List<CredentialSpec> {
        val builtInSpecs = BuiltInPluginRegistry.getBuiltInCredentialSpecs()
        val allManifests = unifiedPluginRegistry.getAllManifests()
        val pluginSpecs = allManifests.mapNotNull { it.credentialSpec }

        val specMap = mutableMapOf<String, CredentialSpec>()
        builtInSpecs.forEach { specMap[it.typeId] = it }
        pluginSpecs.forEach { specMap[it.typeId] = it }

        return specMap.values.toList()
    }

    open suspend fun getCredentialSpecByTypeId(typeId: String): CredentialSpec? {
        val all = getAllCredentialSpecs()
        return all.find { it.typeId == typeId }
    }
}
