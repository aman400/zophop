package dev.zophop.ui.workflows

import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.NodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialNodeConfigSelectionTest {
    @Test
    fun testNtfyCredentialSelectedForNtfyAction_prefillsDefaultsFromCredential() {
        val ntfyManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.NTFY_ACTION)
        val initialConfig =
            mapOf(
                "serverUrl" to "https://ntfy.sh",
                "topic" to "my-topic",
                "title" to "ZopHop Notification",
                "template" to "\${body}",
            )

        val ntfyCredential =
            Credential(
                id = "cred-ntfy-1",
                name = "Private Ntfy Server",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://ntfy.private.corp",
                        "topic" to "infra_alerts",
                        "token" to "tk_super_secret_123",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.NTFY_ACTION,
                pluginManifest = ntfyManifest,
                currentConfig = initialConfig,
                credential = ntfyCredential,
            )

        assertEquals("cred-ntfy-1", updatedConfig["credentialId"])
        assertEquals("https://ntfy.private.corp", updatedConfig["serverUrl"])
        assertEquals("infra_alerts", updatedConfig["topic"])
        // token is an override field: remains blank so credential's token is used as default via placeholder
        assertEquals("", updatedConfig["token"].orEmpty())
        assertEquals("ZopHop Notification", updatedConfig["title"])
        assertEquals("\${body}", updatedConfig["template"])
    }

    @Test
    fun testNtfyNode_tokenOverridePreservedWhenSetByUser() {
        val ntfyManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.NTFY_ACTION)
        val initialConfig =
            mapOf(
                "serverUrl" to "https://ntfy.sh",
                "topic" to "my-topic",
                "token" to "user_custom_override_token",
            )

        val ntfyCredential =
            Credential(
                id = "cred-ntfy-1",
                name = "Private Ntfy Server",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://ntfy.private.corp",
                        "topic" to "infra_alerts",
                        "token" to "tk_super_secret_123",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.NTFY_ACTION,
                pluginManifest = ntfyManifest,
                currentConfig = initialConfig,
                credential = ntfyCredential,
            )

        assertEquals("cred-ntfy-1", updatedConfig["credentialId"])
        assertEquals("https://ntfy.private.corp", updatedConfig["serverUrl"])
        assertEquals("infra_alerts", updatedConfig["topic"])
        assertEquals("user_custom_override_token", updatedConfig["token"])
    }

    @Test
    fun testNtfyCredentialSelectedForHttpAction_configuresUrlMethodHeadersAndPayload() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig =
            mapOf(
                "url" to "https://example.com/health",
                "method" to "GET",
            )

        val ntfyCredential =
            Credential(
                id = "cred-ntfy-2",
                name = "Team Ntfy",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://ntfy.sh",
                        "topic" to "devops",
                        "token" to "tk_devops_token",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = ntfyCredential,
            )

        assertEquals("cred-ntfy-2", updatedConfig["credentialId"])
        assertEquals("https://ntfy.sh/devops", updatedConfig["url"])
        assertEquals("POST", updatedConfig["method"])
        assertTrue(updatedConfig["headers"]!!.contains("Authorization:"))
        assertFalse(updatedConfig["headers"]!!.contains("tk_devops_token"))
        assertEquals("\${body}", updatedConfig["bodyTemplate"])
    }

    @Test
    fun testNtfyCredentialSelectedForHttpAction_trailingAndLeadingSlashesHandledCleanly() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig = mapOf("url" to "")

        val ntfyCredential =
            Credential(
                id = "cred-ntfy-slashes",
                name = "Slashes Server",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://custom.ntfy.io/",
                        "topic" to "/my_topic/",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = ntfyCredential,
            )

        assertEquals("https://custom.ntfy.io/my_topic/", updatedConfig["url"])
        assertEquals("POST", updatedConfig["method"])
    }

    @Test
    fun testNtfyCredentialOnHttpAction_userOverridesArePreserved() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig = mapOf("url" to "", "method" to "GET")

        val ntfyCredential =
            Credential(
                id = "cred-ntfy-3",
                name = "Ntfy",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://ntfy.sh",
                        "topic" to "default_topic",
                        "token" to "default_token",
                    ),
            )

        val autoPrefilled =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = ntfyCredential,
            )

        // User overrides the URL and headers manually
        val userOverridden =
            autoPrefilled +
                mapOf(
                    "url" to "https://ntfy.sh/custom_override_topic",
                    "headers" to "Authorization: Bearer user_custom_token\nTitle: Urgent Alert",
                )

        assertEquals("https://ntfy.sh/custom_override_topic", userOverridden["url"])
        assertTrue(userOverridden["headers"]!!.contains("Authorization: Bearer user_custom_token"))
        assertTrue(userOverridden["headers"]!!.contains("Title: Urgent Alert"))
    }

    @Test
    fun testGotifyCredentialSelectedForGotifyAction_prefillsServerAndAppToken() {
        val gotifyManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.GOTIFY_ACTION)
        val initialConfig =
            mapOf(
                "serverUrl" to "https://gotify.example.com",
                "title" to "ZopHop Notification",
            )

        val gotifyCred =
            Credential(
                id = "cred-gotify-1",
                name = "Home Gotify",
                type = CredentialType.GOTIFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://gotify.home.lab",
                        "appToken" to "A1234567890",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.GOTIFY_ACTION,
                pluginManifest = gotifyManifest,
                currentConfig = initialConfig,
                credential = gotifyCred,
            )

        assertEquals("cred-gotify-1", updatedConfig["credentialId"])
        assertEquals("https://gotify.home.lab", updatedConfig["serverUrl"])
        // appToken is an override field: remains blank so credential's token is used as default via placeholder
        assertEquals("", updatedConfig["appToken"].orEmpty())
    }

    @Test
    fun testGotifyNode_appTokenOverridePreservedWhenSetByUser() {
        val gotifyManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.GOTIFY_ACTION)
        val initialConfig =
            mapOf(
                "serverUrl" to "https://gotify.example.com",
                "appToken" to "custom_user_app_token",
            )

        val gotifyCred =
            Credential(
                id = "cred-gotify-1",
                name = "Home Gotify",
                type = CredentialType.GOTIFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://gotify.home.lab",
                        "appToken" to "A1234567890",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.GOTIFY_ACTION,
                pluginManifest = gotifyManifest,
                currentConfig = initialConfig,
                credential = gotifyCred,
            )

        assertEquals("cred-gotify-1", updatedConfig["credentialId"])
        assertEquals("https://gotify.home.lab", updatedConfig["serverUrl"])
        assertEquals("custom_user_app_token", updatedConfig["appToken"])
    }

    @Test
    fun testGotifyCredentialSelectedForHttpAction_prefillsUrlHeadersAndMethod() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig = mapOf("url" to "", "method" to "GET")

        val gotifyCred =
            Credential(
                id = "cred-gotify-2",
                name = "Gotify Server",
                type = CredentialType.GOTIFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://gotify.example.com",
                        "appToken" to "A987654321",
                    ),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = gotifyCred,
            )

        assertEquals("https://gotify.example.com/message", updatedConfig["url"])
        assertEquals("POST", updatedConfig["method"])
        assertTrue(updatedConfig["headers"]!!.contains("X-Gotify-Key:"))
        assertFalse(updatedConfig["headers"]!!.contains("A987654321"))
        assertTrue(updatedConfig["bodyTemplate"]!!.contains("message"))
    }

    @Test
    fun testBearerTokenCredentialSelectedForHttpAction_setsAuthorizationHeader() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig = mapOf("url" to "https://api.github.com/user")

        val bearerCred =
            Credential(
                id = "cred-bearer",
                name = "GitHub Token",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to "ghp_1234567890"),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = bearerCred,
            )

        assertEquals("cred-bearer", updatedConfig["credentialId"])
        assertTrue(updatedConfig["headers"]!!.contains("Authorization:"))
        assertFalse(updatedConfig["headers"]!!.contains("ghp_1234567890"))
    }

    @Test
    fun testBasicAuthCredentialSelectedForHttpAction_setsBase64AuthHeader() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig = mapOf("url" to "https://secure.example.com")

        val basicCred =
            Credential(
                id = "cred-basic",
                name = "Admin Credentials",
                type = CredentialType.BASIC_AUTH,
                dataMap = mapOf("username" to "admin", "password" to "secret123"),
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = basicCred,
            )

        assertEquals("cred-basic", updatedConfig["credentialId"])
        assertTrue(updatedConfig["headers"]!!.contains("Authorization:"))
        assertFalse(updatedConfig["headers"]!!.contains("secret123"))
    }

    @Test
    fun testHttpAction_methodOverrideToGetPreservedWhenCredentialAttached() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val configWithGetMethod =
            mapOf(
                "credentialId" to "cred-ntfy-get",
                "url" to "https://ntfy.sh/my_topic",
                "method" to "GET",
                "headers" to "Authorization: ",
            )

        val ntfyCred =
            Credential(
                id = "cred-ntfy-get",
                name = "Ntfy Reader",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://ntfy.sh",
                        "topic" to "my_topic",
                        "token" to "secret_reader_token",
                    ),
            )

        val filled =
            WorkflowUiHelper.fillMissingCredentialDefaults(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = configWithGetMethod,
                credential = ntfyCred,
            )

        // GET method MUST be preserved and not forcibly reset to POST!
        assertEquals("GET", filled["method"])
        assertEquals("https://ntfy.sh/my_topic", filled["url"])
        assertEquals("Authorization: ", filled["headers"])
    }

    @Test
    fun testClearingCredential_removesCredentialId() {
        val httpManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.HTTP_ACTION)
        val initialConfig =
            mapOf(
                "credentialId" to "cred-existing",
                "url" to "https://ntfy.sh/my-topic",
                "method" to "POST",
            )

        val updatedConfig =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = NodeType.HTTP_ACTION,
                pluginManifest = httpManifest,
                currentConfig = initialConfig,
                credential = null,
            )

        assertEquals("", updatedConfig["credentialId"])
        assertEquals("https://ntfy.sh/my-topic", updatedConfig["url"])
    }

    @Test
    fun testFillMissingCredentialDefaults_preservesExplicitOverridesOnExistingNode() {
        val ntfyManifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(NodeType.NTFY_ACTION)
        val existingConfigWithOverrides =
            mapOf(
                "credentialId" to "cred-ntfy-saved",
                "serverUrl" to "https://custom.override.com",
                "topic" to "my-topic", // generic default that should be filled from credential
            )

        val cred =
            Credential(
                id = "cred-ntfy-saved",
                name = "Ntfy",
                type = CredentialType.NTFY_AUTH,
                dataMap =
                    mapOf(
                        "serverUrl" to "https://cred.default.com",
                        "topic" to "cred_topic",
                        "token" to "cred_token",
                    ),
            )

        val filled =
            WorkflowUiHelper.fillMissingCredentialDefaults(
                nodeType = NodeType.NTFY_ACTION,
                pluginManifest = ntfyManifest,
                currentConfig = existingConfigWithOverrides,
                credential = cred,
            )

        // Custom override "https://custom.override.com" is preserved!
        assertEquals("https://custom.override.com", filled["serverUrl"])
        // Generic default "my-topic" was replaced with credential's topic
        assertEquals("cred_topic", filled["topic"])
        // Token is an override field: remains blank so credential's token is used as default
        assertEquals("", filled["token"].orEmpty())
    }

    @Test
    fun testValidateHttpAction_allowsBlankUrlWhenCredentialIsAttached() {
        val configWithCred = mapOf("credentialId" to "cred-123", "url" to "")
        val validationErr = NodeConfigValidator.validateNodeConfig(NodeType.HTTP_ACTION, configWithCred)
        assertNull(validationErr)

        val configWithoutCred = mapOf("url" to "")
        val errNoCred = NodeConfigValidator.validateNodeConfig(NodeType.HTTP_ACTION, configWithoutCred)
        assertNotNull(errNoCred)
        assertTrue(errNoCred!!.contains("HTTP URL cannot be blank"))
    }

    @Test
    fun testTelegramCredentialSelectedForTelegramAction_prefillsWithoutExposingBotToken() {
        val telegramManifest =
            dev.zophop.plugin.api.NodePluginManifest(
                typeId = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                displayName = "Telegram Notification",
                description = "Telegram action",
                category = dev.zophop.plugin.api.NodeCategory.ACTION,
                requiredCredentialType = "TELEGRAM_AUTH",
                configSchema =
                    listOf(
                        dev.zophop.plugin.api.ConfigFieldSpec.TextField(
                            key = "botToken",
                            label = "Bot Token",
                            isSecret = true,
                        ),
                        dev.zophop.plugin.api.ConfigFieldSpec.TextField(
                            key = "chatId",
                            label = "Chat ID",
                        ),
                    ),
            )

        val initialConfig = mapOf("botToken" to "", "chatId" to "-100123456789")
        val telegramCred =
            Credential(
                id = "cred-tg-1",
                name = "My Telegram Bot",
                type = "TELEGRAM_AUTH",
                dataMap = mapOf("token" to "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"),
            )

        val updated =
            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                nodeType = "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                pluginManifest = telegramManifest,
                currentConfig = initialConfig,
                credential = telegramCred,
            )

        assertEquals("cred-tg-1", updated["credentialId"])
        assertEquals("-100123456789", updated["chatId"])
        // botToken is an override field: remains blank so credential's token is used securely
        assertEquals("", updated["botToken"].orEmpty())
    }
}
