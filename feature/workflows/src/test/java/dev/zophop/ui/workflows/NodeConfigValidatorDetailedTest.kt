package dev.zophop.ui.workflows

import dev.zophop.domain.model.NodeType
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive automation test suite for [NodeConfigValidator] across all supported workflow node types:
 * - SMS Actions & Triggers
 * - Notification Actions & Triggers
 * - HTTP Webhook Actions
 * - Ntfy & Gotify & Telegram Actions
 * - Regex & JSON Parsers & Template Formatters
 * - Condition & Cron Nodes
 * - Manifest Schema-driven validation
 */
class NodeConfigValidatorDetailedTest {

    @Test
    fun testValidateSmsAction_emptyRecipientOrBody() {
        val err1 = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SEND_SMS_ACTION,
            config = mapOf("recipient" to "", "body" to "Hello"),
        )
        assertNotNull(err1)
        assertTrue(err1!!.contains("Recipient phone number cannot be blank"))

        val err2 = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SEND_SMS_ACTION,
            config = mapOf("recipient" to "+15551234567", "body" to "  "),
        )
        assertNotNull(err2)
        assertTrue(err2!!.contains("Message body cannot be blank"))

        val valid = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SEND_SMS_ACTION,
            config = mapOf("recipient" to "+15551234567", "body" to "Valid SMS"),
        )
        assertNull(valid)
    }

    @Test
    fun testValidateNotificationAction_emptyTitleOrBody() {
        val err1 = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SEND_NOTIFICATION_ACTION,
            config = mapOf("title" to "", "body" to "Hello"),
        )
        assertNotNull(err1)
        assertTrue(err1!!.contains("Notification title cannot be blank"))

        val err2 = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SEND_NOTIFICATION_ACTION,
            config = mapOf("title" to "Alert", "body" to ""),
        )
        assertNotNull(err2)
        assertTrue(err2!!.contains("Notification content body cannot be blank"))

        val valid = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SEND_NOTIFICATION_ACTION,
            config = mapOf("title" to "Alert", "body" to "Content"),
        )
        assertNull(valid)
    }

    @Test
    fun testValidateHttpAction_urlSchemeAndBlank() {
        val errBlank = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.HTTP_ACTION,
            config = mapOf("url" to "  "),
        )
        assertNotNull(errBlank)
        assertTrue(errBlank!!.contains("HTTP URL cannot be blank"))

        val errNonHttp = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.HTTP_ACTION,
            config = mapOf("url" to "ftp://example.com/api"),
        )
        assertNotNull(errNonHttp)
        assertTrue(errNonHttp!!.contains("must start with http:// or https://"))

        val validHttp = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.HTTP_ACTION,
            config = mapOf("url" to "https://api.example.com/v1"),
        )
        assertNull(validHttp)
    }

    @Test
    fun testValidateNtfyAction_credentialOrServerUrl() {
        val errMissingServer = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.NTFY_ACTION,
            config = mapOf("topic" to "my-topic"),
        )
        assertNotNull(errMissingServer)
        assertTrue(errMissingServer!!.contains("Server URL or an attached credential must be provided"))

        val errMissingTopic = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.NTFY_ACTION,
            config = mapOf("serverUrl" to "https://ntfy.sh", "topic" to "  "),
        )
        assertNotNull(errMissingTopic)
        assertTrue(errMissingTopic!!.contains("Topic name cannot be blank"))

        val validWithCred = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.NTFY_ACTION,
            config = mapOf("credentialId" to "cred_123", "topic" to "alerts"),
        )
        assertNull(validWithCred)
    }

    @Test
    fun testValidateGotifyAction_tokensAndServerUrl() {
        val errNoToken = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.GOTIFY_ACTION,
            config = mapOf("serverUrl" to "https://gotify.example.com"),
        )
        assertNotNull(errNoToken)
        assertTrue(errNoToken!!.contains("App Token or an attached credential must be provided"))

        val valid = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.GOTIFY_ACTION,
            config = mapOf("serverUrl" to "https://gotify.example.com", "appToken" to "A1B2C3"),
        )
        assertNull(valid)
    }

    @Test
    fun testValidateTelegramAction_botTokenAndChatId() {
        val errMissingToken = NodeConfigValidator.validateNodeConfig(
            nodeType = "TELEGRAM_ACTION",
            config = mapOf("chatId" to "123456789"),
        )
        assertNotNull(errMissingToken)
        assertTrue(errMissingToken!!.contains("Either Telegram Bot Token or an attached credential must be provided"))

        val errMissingChat = NodeConfigValidator.validateNodeConfig(
            nodeType = "TELEGRAM_ACTION",
            config = mapOf("botToken" to "bot1234:ABC", "chatId" to ""),
        )
        assertNotNull(errMissingChat)
        assertTrue(errMissingChat!!.contains("Telegram Chat ID cannot be blank"))

        val valid = NodeConfigValidator.validateNodeConfig(
            nodeType = "TELEGRAM_ACTION",
            config = mapOf("botToken" to "bot1234:ABC", "chatId" to "123456789"),
        )
        assertNull(valid)
    }

    @Test
    fun testValidateRegexParser_invalidSyntaxHandled() {
        val errInvalidRegex = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.REGEX_PARSER,
            config = mapOf("regex" to "(unclosed_group"),
        )
        assertNotNull(errInvalidRegex)
        assertTrue(errInvalidRegex!!.contains("Invalid regular expression"))

        val valid = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.REGEX_PARSER,
            config = mapOf("regex" to "\\b\\d{4,6}\\b", "outputKey" to "otp"),
        )
        assertNull(valid)
    }

    @Test
    fun testValidateJsonParserAndTemplateFormatter_defaults() {
        val validJson = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.JSON_PARSER,
            config = emptyMap(), // Uses defaults
        )
        assertNull(validJson)

        val validTemplate = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.TEMPLATE_FORMATTER,
            config = emptyMap(), // Uses default ${body}
        )
        assertNull(validTemplate)
    }

    @Test
    fun testValidateSmsAndNotificationTriggers_regexValidation() {
        val errSmsAllowlist = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SMS_TRIGGER,
            config = mapOf("allowlist" to "[unclosed"),
        )
        assertNotNull(errSmsAllowlist)
        assertTrue(errSmsAllowlist!!.contains("Invalid sender allowlist regex"))

        val errSmsKeyword = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.SMS_TRIGGER,
            config = mapOf("keywordRegex" to "(unclosed"),
        )
        assertNotNull(errSmsKeyword)
        assertTrue(errSmsKeyword!!.contains("Invalid keyword regex"))

        val errNotifRegex = NodeConfigValidator.validateNodeConfig(
            nodeType = NodeType.NOTIFICATION_TRIGGER,
            config = mapOf("textRegex" to "(unclosed"),
        )
        assertNotNull(errNotifRegex)
        assertTrue(errNotifRegex!!.contains("Invalid text regex filter"))
    }

    @Test
    fun testValidateSchema_enforcesFieldUrlScheme() {
        val manifest = NodePluginManifest(
            typeId = "custom_http",
            displayName = "Custom HTTP",
            description = "Desc",
            category = NodeCategory.ACTION,
            configSchema = listOf(
                ConfigFieldSpec.TextField(key = "url", label = "URL"),
            ),
        )

        val errNonHttp = NodeConfigValidator.validateNodeConfig(
            nodeType = "custom_http",
            config = mapOf("url" to "javascript:alert(1)"),
            manifest = manifest,
        )
        assertNotNull(errNonHttp)
        assertEquals("URL must start with http:// or https://.", errNonHttp)

        val valid = NodeConfigValidator.validateNodeConfig(
            nodeType = "custom_http",
            config = mapOf("url" to "https://api.example.com"),
            manifest = manifest,
        )
        assertNull(valid)
    }
}
