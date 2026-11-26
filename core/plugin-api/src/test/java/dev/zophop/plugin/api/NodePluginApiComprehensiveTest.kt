package dev.zophop.plugin.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive test suite covering every corner of the :core:plugin-api module:
 * - NodePluginManifest capabilities, triggers, variable extractions, regex groups
 * - ConfigFieldSpec hierarchy (TextField, DropdownField, CodeTemplateField)
 * - CredentialSpec and CredentialField definitions
 * - NodeExecutionResult sealed hierarchy (Success, Failed)
 * - ExecutionContext concurrency and type retrieval
 */
class NodePluginApiComprehensiveTest {

    @Test
    fun testNodeCategory_enumValuesAndOrdering() {
        val categories = NodeCategory.values()
        assertEquals(4, categories.size)
        assertTrue(categories.contains(NodeCategory.TRIGGER))
        assertTrue(categories.contains(NodeCategory.EXTRACTION))
        assertTrue(categories.contains(NodeCategory.LOGIC))
        assertTrue(categories.contains(NodeCategory.ACTION))
    }

    @Test
    fun testConfigFieldSpec_polymorphicHierarchy() {
        val textField = ConfigFieldSpec.TextField(
            key = "url",
            label = "Target URL",
            placeholder = "https://example.com",
            tooltip = "Endpoint URL",
            singleLine = true,
            minLines = 1,
            isTestField = false,
        )
        assertEquals("url", textField.key)
        assertEquals("Target URL", textField.label)
        assertEquals("https://example.com", textField.placeholder)
        assertFalse(textField.isTestField)

        val dropdownField = ConfigFieldSpec.DropdownField(
            key = "method",
            label = "HTTP Method",
            tooltip = "Request Verb",
            options = listOf("GET" to "GET", "POST" to "POST"),
            isTestField = true,
        )
        assertEquals("method", dropdownField.key)
        assertEquals(2, dropdownField.options.size)
        assertTrue(dropdownField.isTestField)

        val codeField = ConfigFieldSpec.CodeTemplateField(
            key = "body",
            label = "Request Body",
            tooltip = "Payload template",
            minLines = 5,
            singleLine = false,
            maxLength = 2048,
        )
        assertEquals("body", codeField.key)
        assertEquals(5, codeField.minLines)
        assertEquals(2048, codeField.maxLength)
    }

    @Test
    fun testCredentialSpec_configuration() {
        val field1 = ConfigFieldSpec.TextField("token", "API Token", tooltip = "Secret token")
        val field2 = ConfigFieldSpec.TextField("endpoint", "Custom Host", tooltip = "Host URL")

        val spec = CredentialSpec(
            typeId = "CUSTOM_OAUTH",
            displayName = "Custom OAuth",
            description = "Custom Bearer Token provider",
            fields = listOf(field1, field2),
        )

        assertEquals("CUSTOM_OAUTH", spec.typeId)
        assertEquals(2, spec.fields.size)
        assertEquals("token", spec.fields[0].key)
        assertEquals("endpoint", spec.fields[1].key)
    }

    @Test
    fun testNodePluginManifest_credentialSupportCheck() {
        val manifestExplicit = NodePluginManifest(
            typeId = "type1",
            displayName = "Name",
            description = "Desc",
            category = NodeCategory.ACTION,
            supportsCredentials = true,
        )
        assertTrue(manifestExplicit.isCredentialSupported())

        val manifestReqType = NodePluginManifest(
            typeId = "type2",
            displayName = "Name",
            description = "Desc",
            category = NodeCategory.ACTION,
            requiredCredentialType = "BEARER",
        )
        assertTrue(manifestReqType.isCredentialSupported())

        val manifestWithSpec = NodePluginManifest(
            typeId = "type3",
            displayName = "Name",
            description = "Desc",
            category = NodeCategory.ACTION,
            credentialSpec = CredentialSpec("SPEC", "Spec", "Desc", emptyList()),
        )
        assertTrue(manifestWithSpec.isCredentialSupported())

        val manifestNone = NodePluginManifest(
            typeId = "type4",
            displayName = "Name",
            description = "Desc",
            category = NodeCategory.ACTION,
        )
        assertFalse(manifestNone.isCredentialSupported())
    }

    @Test
    fun testNodePluginManifest_categoryTypePredicates() {
        val trigger = NodePluginManifest(
            typeId = "trig",
            displayName = "Trigger",
            description = "Desc",
            category = NodeCategory.TRIGGER,
        )
        assertTrue(trigger.isTriggerNode())
        assertFalse(trigger.isRegularNode())

        val action = NodePluginManifest(
            typeId = "act",
            displayName = "Action",
            description = "Desc",
            category = NodeCategory.ACTION,
        )
        assertFalse(action.isTriggerNode())
        assertTrue(action.isRegularNode())
    }

    @Test
    fun testNodePluginManifest_variableExtractionLogic() {
        val manifest = NodePluginManifest(
            typeId = "extractor",
            displayName = "Extractor",
            description = "Extracts dynamic keys",
            category = NodeCategory.EXTRACTION,
            outputVariables = listOf("static_var1", "static_var2"),
        )

        // 1. Static output variables when config is null or blank
        val defaultVars = manifest.getProducedVariables(null)
        assertEquals(listOf("static_var1", "static_var2"), defaultVars)

        // 2. Dynamic outputKey extraction
        val configWithOutputKey = """{"outputKey":"extracted_otp"}"""
        val varsWithKey = manifest.getProducedVariables(configWithOutputKey)
        assertTrue(varsWithKey.contains("extracted_otp"))

        // 3. Object-based mappings
        val configWithObjMappings = """{"mappings":{"userId":"$.user.id","userEmail":"$.user.email"}}"""
        val varsObj = manifest.getProducedVariables(configWithObjMappings)
        assertTrue(varsObj.contains("userId"))
        assertTrue(varsObj.contains("userEmail"))

        // 4. Array-based mappings
        val configWithArrMappings = """{"mappings":[{"outputKey":"fieldA"},{"key":"fieldB"}]}"""
        val varsArr = manifest.getProducedVariables(configWithArrMappings)
        assertTrue(varsArr.contains("fieldA"))
        assertTrue(varsArr.contains("fieldB"))

        // 5. Named regex group extraction
        val configWithRegex = """{"regex":"Your OTP is (?<otp_code>[0-9]{4,6}) for (?<app_name>[a-zA-Z]+)"}"""
        val varsRegex = manifest.getProducedVariables(configWithRegex)
        assertTrue(varsRegex.contains("otp_code"))
        assertTrue(varsRegex.contains("app_name"))

        // 6. Malformed JSON or invalid regex gracefully returns static variables
        val malformedConfig = "{ invalid json"
        val varsMalformed = manifest.getProducedVariables(malformedConfig)
        assertEquals(listOf("static_var1", "static_var2"), varsMalformed)
    }

    @Test
    fun testNodePluginManifest_isSupportedByTriggerMatching() {
        val manifest = NodePluginManifest(
            typeId = "sms_handler",
            displayName = "SMS Handler",
            description = "Desc",
            category = NodeCategory.ACTION,
            supportedTriggerTypes = listOf(
                "SMS_TRIGGER",
                "dev.zophop.plugin.notification.NOTIFICATION_TRIGGER",
            ),
        )

        // Empty filter on another manifest accepts any trigger
        val unconstrainedManifest = NodePluginManifest(
            typeId = "any_handler",
            displayName = "Any",
            description = "Desc",
            category = NodeCategory.ACTION,
        )
        assertTrue(unconstrainedManifest.isSupportedByTrigger("ANY_TRIGGER"))

        // Blank trigger string returns false
        assertFalse(manifest.isSupportedByTrigger(""))
        assertFalse(manifest.isSupportedByTrigger(null))

        // Exact match
        assertTrue(manifest.isSupportedByTrigger("SMS_TRIGGER"))
        // Case-insensitive match
        assertTrue(manifest.isSupportedByTrigger("sms_trigger"))
        // Simple name matching against FQN bound
        assertTrue(manifest.isSupportedByTrigger("NOTIFICATION_TRIGGER"))
        // FQN matching against simple name bound
        assertTrue(manifest.isSupportedByTrigger("dev.zophop.plugin.sms.SMS_TRIGGER"))
        // Non-supported trigger returns false
        assertFalse(manifest.isSupportedByTrigger("CALL_TRIGGER"))
    }

    @Test
    fun testNodeExecutionResult_successFailedAndIgnoredContracts() {
        val success = NodeExecutionResult.Success(
            outputPort = "true",
            logMessage = "Step passed successfully",
        )
        assertEquals("true", success.outputPort)
        assertEquals("Step passed successfully", success.logMessage)

        val failed = NodeExecutionResult.Failed(
            reason = "Timeout exceeded",
        )
        assertEquals("Timeout exceeded", failed.reason)

        val ignored = NodeExecutionResult.Ignored(
            reason = "Filtered out",
        )
        assertEquals("Filtered out", ignored.reason)
    }

    @Test
    fun testExecutionContext_concurrencyAndThreadSafety() = runTest {
        val context = ExecutionContext()
        val jobCount = 50

        // Launch concurrent writes across multiple coroutines
        val jobs = (1..jobCount).map { i ->
            launch(Dispatchers.Default) {
                context.set("key_$i", "val_$i")
                context.set("shared_counter", i)
            }
        }
        jobs.forEach { it.join() }

        val allVars = context.getAll()
        assertEquals(jobCount + 1, allVars.size)
        assertTrue(allVars.containsKey("key_1"))
        assertTrue(allVars.containsKey("key_$jobCount"))
        assertNotNull(context.get("shared_counter"))
    }

    @Test
    fun testExecutionLogFormatter_redactsUrlsTokensHeadersAndExplicitSecrets() {
        val rawUrl = "https://alice:s3cr3t@api.telegram.org/bot123456:ABC-XYZ_token/sendMessage?access_token=tok_val&mode=1"
        val safeUrl = ExecutionLogFormatter.redactUrl(rawUrl)
        assertFalse(safeUrl.contains("s3cr3t"))
        assertFalse(safeUrl.contains("123456:ABC-XYZ_token"))
        assertFalse(safeUrl.contains("tok_val"))
        assertTrue(safeUrl.contains("mode=1"))

        val rawLog =
            """
            Authorization: Bearer my_jwt_token_value
            X-Gotify-Key: gotify_key_value
            Set-Cookie: session=cookie_secret_val
            Error reaching $rawUrl with explicit=secret+with spaces
            """.trimIndent()
        val redacted =
            ExecutionLogFormatter.redactSecrets(
                rawLog,
                listOf("secret+with spaces", "my_jwt_token_value"),
            )
        assertFalse(redacted.contains("my_jwt_token_value"))
        assertFalse(redacted.contains("gotify_key_value"))
        assertFalse(redacted.contains("cookie_secret_val"))
        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("123456:ABC-XYZ_token"))
        assertFalse(redacted.contains("tok_val"))
        assertFalse(redacted.contains("secret+with spaces"))
        assertFalse(redacted.contains("secret%2Bwith+spaces"))
    }
}
