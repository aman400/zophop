package dev.zophop.domain.engine.plugin

import dev.zophop.data.CredentialRepository
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import okhttp3.OkHttpClient
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

    fun discoverBundledModules() {
        discoverAndRegisterBundledModules()
    }

    private fun discoverAndRegisterBundledModules() {
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            BuiltInPluginRegistry::class.java.classLoader,
            NodePluginModule::class.java.classLoader,
            ClassLoader.getSystemClassLoader(),
        ).distinct()

        for (classLoader in loaders) {
            try {
                val loader = ServiceLoader.load(NodePluginModule::class.java, classLoader)
                for (module in loader) {
                    registerModule(module)
                }
            } catch (_: java.util.ServiceConfigurationError) {
            } catch (_: LinkageError) {
            }
        }

        discoverViaMetaInfServices(loaders)
    }

    private fun discoverViaMetaInfServices(loaders: List<ClassLoader>) {
        val serviceResource = "META-INF/services/dev.zophop.plugin.api.NodePluginModule"
        for (loader in loaders) {
            readServiceResourceLines(loader, serviceResource)
        }
    }

    private fun readServiceResourceLines(loader: ClassLoader, resource: String) {
        try {
            val urls = loader.getResources(resource)
            while (urls.hasMoreElements()) {
                val url = urls.nextElement()
                parseServiceUrl(url, loader)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseServiceUrl(url: java.net.URL, loader: ClassLoader) {
        try {
            url.openStream()?.bufferedReader()?.useLines { lines ->
                for (rawLine in lines) {
                    val line = rawLine.substringBefore('#').trim()
                    if (line.isNotEmpty()) {
                        val module = tryLoadModuleClass(line, loader)
                        if (module != null) {
                            registerModule(module)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun tryLoadModuleClass(className: String, loader: ClassLoader): NodePluginModule? =
        try {
            val clazz = Class.forName(className, true, loader)
            resolveSingletonInstance(clazz) as? NodePluginModule
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
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
