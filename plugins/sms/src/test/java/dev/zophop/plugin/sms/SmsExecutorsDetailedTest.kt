package dev.zophop.plugin.sms

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Detailed unit test suite testing [SmsTriggerExecutor] and [SendSmsExecutor]
 * under diverse configurations, permissions, allowlists, and edge cases.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SmsExecutorsDetailedTest {
    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSmsTrigger_successfulExecutionAndContextPopulation() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)

        val executor = SmsTriggerExecutor()
        val node = WorkflowNode(
            id = "n_sms",
            name = "SMS Trigger",
            type = NodeType.SMS_TRIGGER,
            configJson = """{"allowlist":"*BANK*,VERIFY","keywordRegex":".*OTP.*"}""",
        )

        val context = ExecutionContext(mapOf("sender" to "MYBANK_SMS", "body" to "Your OTP is 492104"))
        val result = executor.execute(node, context, app)

        assertTrue(result is NodeExecutionResult.Success)
        assertEquals("MYBANK_SMS", context.getString("sender"))
        assertEquals("MYBANK_SMS", context.getString("header"))
        assertEquals("MYBANK_SMS", context.getString("sms_header"))
        assertEquals("MYBANK_SMS", context.getString("address"))
        assertEquals("Your OTP is 492104", context.getString("body"))
        assertEquals("Your OTP is 492104", context.getString("sms_body"))
        assertEquals("Your OTP is 492104", context.getString("message"))
        assertEquals("Your OTP is 492104", context.getString("text"))
    }

    @Test
    fun testSmsTrigger_nonMatchingSenderReturnsIgnored() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)

        val executor = SmsTriggerExecutor()
        val node = WorkflowNode(
            id = "n_sms",
            name = "SMS Trigger",
            type = NodeType.SMS_TRIGGER,
            configJson = """{"allowlist":"GOOG,APPLE","keywordRegex":".*"}""",
        )

        val context = ExecutionContext(mapOf("sender" to "SPAMMER", "body" to "Win prize"))
        val result = executor.execute(node, context, app)

        assertTrue(result is NodeExecutionResult.Ignored)
        assertTrue((result as NodeExecutionResult.Ignored).reason.contains("did not match allowlist"))
    }

    @Test
    fun testSmsTrigger_nonMatchingKeywordReturnsIgnored() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)

        val executor = SmsTriggerExecutor()
        val node = WorkflowNode(
            id = "n_sms",
            name = "SMS Trigger",
            type = NodeType.SMS_TRIGGER,
            configJson = """{"allowlist":".*","keywordRegex":".*\\bSECRET\\b.*"}""",
        )

        val context = ExecutionContext(mapOf("sender" to "FRIEND", "body" to "Hey, are you free?"))
        val result = executor.execute(node, context, app)

        assertTrue(result is NodeExecutionResult.Ignored)
        assertTrue((result as NodeExecutionResult.Ignored).reason.contains("did not match keyword regex"))
    }

    @Test
    fun testSmsTrigger_invalidKeywordRegexReturnsFailed() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECEIVE_SMS)

        val executor = SmsTriggerExecutor()
        val node = WorkflowNode(
            id = "n_sms",
            name = "SMS Trigger",
            type = NodeType.SMS_TRIGGER,
            configJson = """{"allowlist":".*","keywordRegex":"(unclosed_group"}""",
        )

        val context = ExecutionContext(mapOf("sender" to "TEST", "body" to "Body"))
        val result = executor.execute(node, context, app)

        assertTrue(result is NodeExecutionResult.Failed)
        assertTrue((result as NodeExecutionResult.Failed).reason.contains("Invalid keyword regex"))
    }

    @Test
    fun testSendSmsExecutor_emptyRecipientFails() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)

        val executor = SendSmsExecutor()
        val node = WorkflowNode(
            id = "n_send",
            name = "Send SMS",
            type = NodeType.SEND_SMS_ACTION,
            configJson = """{"recipient":"","body":"Alert"}""",
        )

        val context = ExecutionContext()
        val result = executor.execute(node, context, app)

        assertTrue(result is NodeExecutionResult.Failed)
        assertTrue((result as NodeExecutionResult.Failed).reason.contains("recipient or body is empty"))
    }

    @Test
    fun testSendSmsExecutor_emptyBodyFails() = runTest {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)

        val executor = SendSmsExecutor()
        val node = WorkflowNode(
            id = "n_send",
            name = "Send SMS",
            type = NodeType.SEND_SMS_ACTION,
            configJson = """{"recipient":"+15551234567","body":"   "}""",
        )

        val context = ExecutionContext()
        val result = executor.execute(node, context, app)

        assertTrue(result is NodeExecutionResult.Failed)
        assertTrue((result as NodeExecutionResult.Failed).reason.contains("recipient or body is empty"))
    }
}
