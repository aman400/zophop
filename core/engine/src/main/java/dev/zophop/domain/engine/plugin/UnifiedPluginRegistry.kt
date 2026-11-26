package dev.zophop.domain.engine.plugin

import android.content.Context
import dev.zophop.plugin.api.NodePluginManifest

open class UnifiedPluginRegistry(
    private val context: Context?,
) {
    private val externalPluginRegistry = ExternalPluginRegistry(context)

    open suspend fun getAllManifests(): List<NodePluginManifest> {
        val externalInfos = externalPluginRegistry.discoverPlugins()
        val externalManifests = externalInfos.map { it.manifest }
        val builtInManifests = BuiltInPluginRegistry.getBuiltInManifests()

        val manifestMap = linkedMapOf<String, NodePluginManifest>()

        // 1. Add built-in manifests first
        for (manifest in builtInManifests) {
            manifestMap[manifest.typeId] = manifest
        }

        // 2. Add external manifests, deduplicating by typeId or (displayName + category)
        for (manifest in externalManifests) {
            val existingKey =
                manifestMap.entries
                    .find { (k, v) ->
                        k.equals(manifest.typeId, ignoreCase = true) ||
                            k.endsWith(".${manifest.typeId}", ignoreCase = true) ||
                            manifest.typeId.endsWith(".$k", ignoreCase = true) ||
                            (v.displayName == manifest.displayName && v.category == manifest.category)
                    }?.key

            if (existingKey != null) {
                manifestMap[existingKey] = manifest
            } else {
                manifestMap[manifest.typeId] = manifest
            }
        }

        return manifestMap.values.toList()
    }

    open suspend fun getManifestByTypeId(typeId: String): NodePluginManifest? {
        val builtIn = BuiltInPluginRegistry.getBuiltInManifestByTypeId(typeId)
        if (builtIn != null) return builtIn

        val externalInfos = externalPluginRegistry.discoverPlugins()
        return externalInfos
            .find {
                it.manifest.typeId.equals(typeId, ignoreCase = true) ||
                    it.manifest.typeId.endsWith(".$typeId", ignoreCase = true) ||
                    typeId.endsWith(".${it.manifest.typeId}", ignoreCase = true)
            }?.manifest
    }

    open fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        for (module in BuiltInPluginRegistry.getRegisteredModules()) {
            val error = module.validateConfig(typeId, configJson)
            if (error != null) return error
        }
        return null
    }

    open suspend fun getMissingPermissions(
        context: Context,
        typeId: String,
    ): List<String> {
        val manifest = getManifestByTypeId(typeId) ?: return emptyList()
        val required = manifest.requiredPermissions
        if (required.isEmpty()) return emptyList()

        val missing = mutableListOf<String>()
        for (perm in required) {
            val granted =
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    perm,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                missing.add(perm)
            }
        }
        return missing
    }
}
