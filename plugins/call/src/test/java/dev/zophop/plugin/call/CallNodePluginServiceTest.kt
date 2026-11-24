package dev.zophop.plugin.call

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optJsonObject
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class CallNodePluginServiceTest {
    @Test
    fun testCallPluginApiVersionContract() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        assertEquals(1, binder.getPluginApiVersion())
    }

    @Test
    fun testCallPluginManifestStructure() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(3, manifests.size)
        assertEquals("dev.zophop.plugin.call.CALL_TRIGGER", manifests[0].typeId)
        assertEquals("Incoming Call Trigger", manifests[0].displayName)
        assertTrue(manifests[0].isTriggerNode())
        assertFalse(manifests[0].isRegularNode())

        assertEquals("dev.zophop.plugin.call.DISCONNECT_CALL_ACTION", manifests[1].typeId)
        assertEquals("Disconnect Call Action", manifests[1].displayName)
        assertTrue(manifests[1].isRegularNode())
        assertEquals(listOf("dev.zophop.plugin.call.CALL_TRIGGER"), manifests[1].supportedTriggerTypes)
        assertTrue(manifests[1].isSupportedByTrigger("dev.zophop.plugin.call.CALL_TRIGGER"))
        assertTrue(manifests[1].isSupportedByTrigger("CALL_TRIGGER"))
        assertFalse(manifests[1].isSupportedByTrigger("dev.zophop.plugin.sms.SMS_TRIGGER"))
        assertFalse(manifests[1].isSupportedByTrigger("SMS_TRIGGER"))
        assertFalse(manifests[1].isSupportedByTrigger("NOTIFICATION_TRIGGER"))
        assertFalse(manifests[1].isSupportedByTrigger(null))

        assertEquals("dev.zophop.plugin.call.ANSWER_CALL_ACTION", manifests[2].typeId)
        assertEquals("Answer Call Action", manifests[2].displayName)
        assertTrue(manifests[2].isRegularNode())
        assertEquals(listOf("dev.zophop.plugin.call.CALL_TRIGGER"), manifests[2].supportedTriggerTypes)
        assertTrue(manifests[2].isSupportedByTrigger("dev.zophop.plugin.call.CALL_TRIGGER"))
        assertTrue(manifests[2].isSupportedByTrigger("CALL_TRIGGER"))
        assertFalse(manifests[2].isSupportedByTrigger("SMS_TRIGGER"))
        assertFalse(manifests[2].isSupportedByTrigger(null))
    }

    @Test
    fun testCallPluginModuleCreateExecutor() {
        val okHttpClient = okhttp3.OkHttpClient()
        val triggerExec = CallPluginModule.createExecutor("dev.zophop.plugin.call.CALL_TRIGGER", okHttpClient, null)
        assertNotNull(triggerExec)
        assertTrue(triggerExec is CallTriggerExecutor)

        val disconnectExec = CallPluginModule.createExecutor("dev.zophop.plugin.call.DISCONNECT_CALL_ACTION", okHttpClient, null)
        assertNotNull(disconnectExec)
        assertTrue(disconnectExec is DisconnectCallExecutor)

        val answerExec = CallPluginModule.createExecutor("dev.zophop.plugin.call.ANSWER_CALL_ACTION", okHttpClient, null)
        assertNotNull(answerExec)
        assertTrue(answerExec is AnswerCallExecutor)
    }

    @Test
    fun testCallTriggerExecutorDirectMatch() =
        runTest {
            val executor = CallTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "c_node",
                    name = "Call",
                    type = "dev.zophop.plugin.call.CALL_TRIGGER",
                    configJson = """{"callerRegex":".*555.*"}""",
                )
            val context = ExecutionContext(mapOf("caller_number" to "+15559876543"))

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("+15559876543", context.getString("caller_number"))
            assertEquals("+15559876543", context.getString("sender"))
        }

    @Test
    fun testCallTriggerExecutorDirectIgnoredWhenNonMatching() =
        runTest {
            val executor = CallTriggerExecutor()
            val node =
                WorkflowNode(
                    id = "c_node",
                    name = "Call",
                    type = "dev.zophop.plugin.call.CALL_TRIGGER",
                    configJson = """{"callerRegex":".*999.*"}""",
                )
            val context = ExecutionContext(mapOf("caller_number" to "+15551234567"))

            val result = executor.execute(node, context, null)
            assertTrue("Non-matching caller must return Ignored (not Failed)", result is NodeExecutionResult.Ignored)
        }

    @Test
    fun testCallTriggerMatchesCallerRegexFilterWhenPermissionGranted() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.call.CALL_TRIGGER","callerRegex":".*555.*"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_trigger", configJson, inputContextJson)
        assertNotNull(resultJson)

        val json = parseJsonObjectSafe(resultJson)
        assertTrue(json.optBoolean("isSuccess"))
        val outputMap = json.optJsonObject("outputDataMap")!!
        assertEquals("+15559876543", outputMap.optString("caller_number"))
        assertEquals("+15559876543", outputMap.optString("sender"))
    }

    @Test
    fun testCallTriggerRejectsNonMatchingCallerNumber() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.call.CALL_TRIGGER","callerRegex":".*999.*"}"""
        val inputContextJson = """{"caller_number":"+15551234567"}"""

        val resultJson = binder.executeNode("node_trigger", configJson, inputContextJson)
        val json = parseJsonObjectSafe(resultJson)

        assertFalse(json.optBoolean("isSuccess"))
        assertTrue(json.optBoolean("isIgnored", false))
        assertTrue(json.optString("errorReason").contains("did not match filter regex"))
    }

    @Test
    fun testCallTriggerFailsWhenReadPhoneStatePermissionMissing() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.denyPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.call.CALL_TRIGGER","callerRegex":".*"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_trigger", configJson, inputContextJson)
        val json = parseJsonObjectSafe(resultJson)

        assertFalse(json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("READ_PHONE_STATE permission not granted"))
    }

    @Test
    fun testAnswerCallActionFailsWhenAnswerCallsPermissionMissing() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.denyPermissions(Manifest.permission.ANSWER_PHONE_CALLS)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.call.ANSWER_CALL_ACTION"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_answer", configJson, inputContextJson)
        val json = parseJsonObjectSafe(resultJson)

        assertFalse(json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("ANSWER_PHONE_CALLS permission not granted"))
    }

    @Test
    fun testDisconnectCallActionFailsWhenPermissionMissing() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
        shadowApp.denyPermissions(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.call.DISCONNECT_CALL_ACTION"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_disconnect", configJson, inputContextJson)
        val json = parseJsonObjectSafe(resultJson)

        assertFalse(json.optBoolean("isSuccess"))
    }

    @Test
    fun testCorruptConfigOrContextFallbackHandling() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val corruptConfigJson = "INVALID_CORRUPT_JSON"
        val corruptInputJson = "INVALID_CORRUPT_JSON"

        val resultJson = binder.executeNode("node_corrupt", corruptConfigJson, corruptInputJson)
        assertNotNull(resultJson)

        val json = parseJsonObjectSafe(resultJson)
        assertTrue(json.containsKey("isSuccess"))
    }
}
