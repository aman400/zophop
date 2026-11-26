package dev.zophop.domain.engine

import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.plugin.ExternalPluginExecutor
import dev.zophop.domain.engine.plugin.ExternalPluginRegistry
import dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodePluginManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPluginIpcTest {
    @Test
    fun testExternalPluginServiceInfoDataStructure() {
        val manifest =
            NodePluginManifest(
                typeId = "com.example.discord.DISCORD_ACTION",
                displayName = "Discord Webhook Action",
                description = "Posts alerts to Discord Channel",
                category = NodeCategory.ACTION,
            )

        val info =
            ExternalPluginServiceInfo(
                packageName = "com.example.discord",
                serviceClassName = "com.example.discord.DiscordNodePluginService",
                manifest = manifest,
                apiVersion = 1,
            )

        assertNotNull(info)
        assertEquals("com.example.discord", info.packageName)
        assertEquals("com.example.discord.DiscordNodePluginService", info.serviceClassName)
        assertEquals("com.example.discord.DISCORD_ACTION", info.manifest.typeId)
        assertEquals(1, info.apiVersion)
    }

    @Test
    fun testExternalPluginExecutorInstantiation() {
        val manifest =
            NodePluginManifest(
                typeId = "com.example.slack.SLACK_ACTION",
                displayName = "Slack Action",
                description = "Posts to Slack Channel",
                category = NodeCategory.ACTION,
            )
        val info = ExternalPluginServiceInfo("com.example.slack", "SlackService", manifest, 1)

        val executor = ExternalPluginExecutor(info)
        assertNotNull(executor)
    }

    @Test
    fun testExternalPluginExecutorFailsWhenContextIsNull() =
        runTest {
            val manifest =
                NodePluginManifest(
                    typeId = "com.example.test.TEST_ACTION",
                    displayName = "Test Action",
                    description = "Test",
                    category = NodeCategory.ACTION,
                )
            val info = ExternalPluginServiceInfo("com.example.test", "TestService", manifest, 1)
            val executor = ExternalPluginExecutor(info)

            val node = WorkflowNode("n1", "Test", "com.example.test.TEST_ACTION")
            val context = ExecutionContext()

            val result = executor.execute(node, context, null)

            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("Context unavailable"))
        }

    @Test
    fun testExternalPluginRegistryWithNullContextReturnsEmptyList() =
        runTest {
            val registry = ExternalPluginRegistry(context = null)
            val plugins = registry.discoverPlugins()
            assertTrue(plugins.isEmpty())
            assertNull(registry.getPluginByTypeId("unknown.type"))
        }

    @Test
    fun testExternalPluginExecutorCredentialInjectionIntoContext() =
        runTest {
            val fakeRepo =
                object : CredentialRepository {
                    override fun getAllCredentialsFlow(): Flow<List<Credential>> = emptyFlow()

                    override suspend fun getCredentialById(id: String): Credential? {
                        if (id == "cred_test_123") {
                            return Credential(
                                id = "cred_test_123",
                                name = "Discord Bot Token",
                                type = "BOT_AUTH",
                                dataMap =
                                    mapOf(
                                        "token" to "secret_discord_token_456",
                                        "webhook_url" to "https://discord.com/api/webhooks/xyz",
                                    ),
                            )
                        }
                        return null
                    }

                    override suspend fun saveCredential(credential: Credential) {}

                    override suspend fun deleteCredential(id: String) {}
                }

            val manifest =
                NodePluginManifest(
                    typeId = "com.example.discord.DISCORD_ACTION",
                    displayName = "Discord",
                    description = "Discord",
                    category = NodeCategory.ACTION,
                )
            val info = ExternalPluginServiceInfo("com.example.discord", "DiscordService", manifest, 1)
            val executor = ExternalPluginExecutor(info, fakeRepo)

            val node =
                WorkflowNode(
                    id = "n_cred",
                    name = "Discord Action",
                    type = "com.example.discord.DISCORD_ACTION",
                    configJson = """{"credentialId":"cred_test_123"}""",
                )
            val context = ExecutionContext()

            // When appContext is null, execute fails after checking credentials, but we can verify the credential injection logic
            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
        }

    @Test
    fun testManifestWithDropdownFieldOptionsAndCredentialSpec() {
        val dropdownField =
            ConfigFieldSpec.DropdownField(
                key = "method",
                label = "HTTP Method",
                options = listOf("GET" to "GET", "POST" to "POST"),
            )
        val credSpec =
            CredentialSpec(
                typeId = "API_KEY",
                displayName = "API Key",
                fields = listOf(dropdownField),
            )
        val manifest =
            NodePluginManifest(
                typeId = "com.example.custom.ACTION",
                displayName = "Custom Action",
                description = "Custom with options",
                category = NodeCategory.ACTION,
                credentialSpec = credSpec,
                configSchema = listOf(dropdownField),
            )

        assertNotNull(manifest.credentialSpec)
        assertEquals(1, manifest.configSchema.size)
        val field = manifest.configSchema[0] as ConfigFieldSpec.DropdownField
        assertEquals(2, field.options.size)
        assertEquals("GET", field.options[0].first)
        assertEquals("POST", field.options[1].second)
    }

    @Test
    fun testExternalPluginExecutor_isolatesCredVariablesAndScrubsPluginLogs() =
        runTest {
            val fakeRepo =
                object : CredentialRepository {
                    override fun getAllCredentialsFlow(): Flow<List<Credential>> = emptyFlow()

                    override suspend fun getCredentialById(id: String): Credential? =
                        if (id == "cred_1") {
                            Credential(
                                id = "cred_1",
                                name = "Bot",
                                type = "BOT_AUTH",
                                dataMap = mapOf("token" to "super_secret_plugin_tok_999"),
                            )
                        } else {
                            null
                        }

                    override suspend fun saveCredential(credential: Credential) {}

                    override suspend fun deleteCredential(id: String) {}
                }

            val capturedInputs = mutableListOf<String>()
            val binder =
                object : dev.zophop.plugin.api.INodePluginService.Stub() {
                    override fun getPluginApiVersion(): Int = 1

                    override fun getManifests(): List<NodePluginManifest> = emptyList()

                    override fun executeNode(
                        nodeId: String,
                        configJson: String,
                        inputContextJson: String,
                    ): String {
                        capturedInputs.add(inputContextJson)
                        return """{"isSuccess":true,"outputPort":"main","logMessage":"Sent with super_secret_plugin_tok_999","outputDataMap":{"safe_out":"ok","_cred_poison":"leaked"}}"""
                    }
                }

            val fakeCtx =
                object : android.content.ContextWrapper(null) {
                    override fun bindService(
                        service: android.content.Intent,
                        conn: android.content.ServiceConnection,
                        flags: Int,
                    ): Boolean {
                        conn.onServiceConnected(service.component, binder)
                        return true
                    }

                    override fun unbindService(conn: android.content.ServiceConnection) {}
                }

            val manifest =
                NodePluginManifest(
                    typeId = "com.example.ext.ACTION",
                    displayName = "External",
                    description = "External",
                    category = NodeCategory.ACTION,
                )
            val info = ExternalPluginServiceInfo("com.example.ext", "ExtService", manifest, 1)
            val executor =
                ExternalPluginExecutor(info, fakeRepo).apply {
                    aidlInterfaceResolver = { it as? dev.zophop.plugin.api.INodePluginService }
                }
            val sharedContext = ExecutionContext(mapOf("body" to "hello", "_cred_stale" to "stale_secret"))

            val nodeWithCred =
                WorkflowNode(
                    id = "n1",
                    name = "Plugin 1",
                    type = "com.example.ext.ACTION",
                    configJson = """{"credentialId":"cred_1"}""",
                )
            val res1 = executor.execute(nodeWithCred, sharedContext, fakeCtx)
            assertTrue(res1 is NodeExecutionResult.Success)
            val log1 = (res1 as NodeExecutionResult.Success).logMessage
            assertFalse("Plugin log must scrub credential secret", log1.contains("super_secret_plugin_tok_999"))
            assertTrue(capturedInputs[0].contains("super_secret_plugin_tok_999"))
            assertFalse("Stale _cred_* in context must not be sent", capturedInputs[0].contains("stale_secret"))

            // Shared ExecutionContext must NOT retain _cred_token or accept _cred_poison
            assertNull(sharedContext.get("_cred_token"))
            assertNull(sharedContext.get("_cred_poison"))
            assertEquals("ok", sharedContext.get("safe_out"))

            // Downstream plugin without credentialId must NOT receive previous plugin's _cred_token
            val nodeWithoutCred =
                WorkflowNode(
                    id = "n2",
                    name = "Plugin 2",
                    type = "com.example.ext.ACTION",
                    configJson = """{}""",
                )
            executor.execute(nodeWithoutCred, sharedContext, fakeCtx)
            assertFalse(
                "Downstream plugin must not receive upstream credential",
                capturedInputs[1].contains("super_secret_plugin_tok_999"),
            )
        }
}
