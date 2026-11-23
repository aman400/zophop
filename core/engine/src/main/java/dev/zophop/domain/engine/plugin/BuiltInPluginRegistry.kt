package dev.zophop.domain.engine.plugin

import dev.zophop.data.CredentialRepository
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.ServiceLoader
import java.util.concurrent.CopyOnWriteArrayList

object BuiltInPluginRegistry {
    private val registeredModules =
        CopyOnWriteArrayList<NodePluginModule>().apply {
            add(CoreEngineNodesModule)
        }

    init {
        discoverAndRegisterBundledModules()
    }

    fun registerModule(module: NodePluginModule) {
        if (registeredModules.none { it.moduleId == module.moduleId }) {
            registeredModules.add(module)
        }
    }

    fun unregisterModule(moduleId: String) {
        registeredModules.removeAll { it.moduleId == moduleId }
    }

    fun getRegisteredModules(): List<NodePluginModule> = registeredModules.toList()

    private fun discoverAndRegisterBundledModules() {
        try {
            val loader = ServiceLoader.load(NodePluginModule::class.java, NodePluginModule::class.java.classLoader)
            for (module in loader) {
                registerModule(module)
            }
        } catch (_: java.util.ServiceConfigurationError) {
        } catch (_: LinkageError) {
        }

        val pluginNames = listOf("sms", "ntfy", "gotify", "gps", "call", "notification", "telegram")
        for (name in pluginNames) {
            val module = loadBundledModule(name)
            if (module != null) {
                registerModule(module)
            }
        }
    }

    private fun loadBundledModule(name: String): NodePluginModule? {
        val capitalized = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        val className = "dev.zophop.plugin.$name.${capitalized}PluginModule"
        return try {
            val clazz = Class.forName(className)
            val instance = resolveSingletonInstance(clazz)
            instance as? NodePluginModule
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun resolveSingletonInstance(clazz: Class<*>): Any? = try {
        clazz.getField("INSTANCE").get(null)
    } catch (_: ReflectiveOperationException) {
        clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    }

    fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: CredentialRepository?,
    ): NodeExecutor? {
        for (module in registeredModules) {
            val executor = module.createExecutor(typeId, okHttpClient, credentialRepository)
            if (executor != null) return executor
        }
        return null
    }

    fun getBuiltInManifests(): List<NodePluginManifest> = registeredModules.flatMap { it.getManifests() }

    fun getBuiltInCredentialSpecs(): List<CredentialSpec> = registeredModules.flatMap { it.getCredentialSpecs() }

    fun getBuiltInManifestByTypeId(typeId: String): NodePluginManifest? =
        getBuiltInManifests().find {
            it.typeId.equals(typeId, ignoreCase = true) ||
                it.typeId.endsWith(".$typeId", ignoreCase = true) ||
                typeId.endsWith(".${it.typeId}", ignoreCase = true)
        }
}
