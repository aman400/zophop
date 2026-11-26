package dev.zophop.plugin.api

import okhttp3.OkHttpClient

interface NodePluginModule {
    val moduleId: String

    fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? = null

    fun getManifests(): List<NodePluginManifest>

    fun getCredentialSpecs(): List<CredentialSpec> = emptyList()

    fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? = null

    fun getHttpCredentialAdapters(): List<HttpCredentialAdapter> = emptyList()
}
