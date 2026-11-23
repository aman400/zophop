package dev.zophop.plugin.call.callplugin

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.plugin.api.INodePluginService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class CallNodePluginServiceTest {
    @Test
    fun testCallPluginApiVersionContract() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as INodePluginService

        assertEquals(1, binder.getPluginApiVersion())
    }

    @Test
    fun testCallPluginManifestStructure() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as INodePluginService
        val manifests = binder.getManifests()

        assertEquals(3, manifests.size)
        assertEquals("dev.zophop.callplugin.CALL_TRIGGER", manifests[0].typeId)
        assertEquals("Incoming Call Trigger", manifests[0].displayName)

        assertEquals("dev.zophop.callplugin.DISCONNECT_CALL_ACTION", manifests[1].typeId)
        assertEquals("Disconnect Call Action", manifests[1].displayName)
        assertEquals(listOf("dev.zophop.callplugin.CALL_TRIGGER"), manifests[1].supportedTriggerTypes)
        assertTrue(manifests[1].isSupportedByTrigger("dev.zophop.callplugin.CALL_TRIGGER"))
        assertTrue(manifests[1].isSupportedByTrigger("CALL_TRIGGER"))
        assertFalse(manifests[1].isSupportedByTrigger("SMS_TRIGGER"))

        assertEquals("dev.zophop.callplugin.ANSWER_CALL_ACTION", manifests[2].typeId)
        assertEquals("Answer Call Action", manifests[2].displayName)
        assertEquals(listOf("dev.zophop.callplugin.CALL_TRIGGER"), manifests[2].supportedTriggerTypes)
        assertTrue(manifests[2].isSupportedByTrigger("dev.zophop.callplugin.CALL_TRIGGER"))
        assertTrue(manifests[2].isSupportedByTrigger("CALL_TRIGGER"))
        assertFalse(manifests[2].isSupportedByTrigger("SMS_TRIGGER"))
    }

    @Test
    fun testCallTriggerMatchesCallerRegexFilterWhenPermissionGranted() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.callplugin.CALL_TRIGGER","callerRegex":".*555.*"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_trigger", configJson, inputContextJson)
        assertNotNull(resultJson)

        val json = JSONObject(resultJson)
        assertTrue(json.getBoolean("isSuccess"))
        val outputMap = json.getJSONObject("outputDataMap")
        assertEquals("+15559876543", outputMap.getString("caller_number"))
        assertEquals("+15559876543", outputMap.getString("sender"))
    }

    @Test
    fun testCallTriggerRejectsNonMatchingCallerNumber() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.callplugin.CALL_TRIGGER","callerRegex":".*999.*"}"""
        val inputContextJson = """{"caller_number":"+15551234567"}"""

        val resultJson = binder.executeNode("node_trigger", configJson, inputContextJson)
        val json = JSONObject(resultJson)

        assertFalse(json.getBoolean("isSuccess"))
        assertTrue(json.getString("errorReason").contains("did not match filter regex"))
    }

    @Test
    fun testCallTriggerFailsWhenReadPhoneStatePermissionMissing() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.denyPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.callplugin.CALL_TRIGGER","callerRegex":".*"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_trigger", configJson, inputContextJson)
        val json = JSONObject(resultJson)

        assertFalse(json.getBoolean("isSuccess"))
        assertTrue(json.getString("errorReason").contains("READ_PHONE_STATE permission not granted"))
    }

    @Test
    fun testAnswerCallActionFailsWhenAnswerCallsPermissionMissing() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.denyPermissions(Manifest.permission.ANSWER_PHONE_CALLS)

        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.callplugin.ANSWER_CALL_ACTION"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_answer", configJson, inputContextJson)
        val json = JSONObject(resultJson)

        assertFalse(json.getBoolean("isSuccess"))
        assertTrue(json.getString("errorReason").contains("ANSWER_PHONE_CALLS permission not granted"))
    }

    @Test
    fun testDisconnectCallActionFailsWhenPermissionMissing() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        shadowApp.denyPermissions(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.callplugin.DISCONNECT_CALL_ACTION"}"""
        val inputContextJson = """{"caller_number":"+15559876543"}"""

        val resultJson = binder.executeNode("node_disconnect", configJson, inputContextJson)
        val json = JSONObject(resultJson)

        assertFalse(json.getBoolean("isSuccess"))
    }

    @Test
    fun testCorruptConfigOrContextFallbackHandling() {
        val serviceController = Robolectric.buildService(CallNodePluginService::class.java).create()
        val service = serviceController.get()

        val shadowApp = Shadows.shadowOf(service)
        shadowApp.grantPermissions(Manifest.permission.READ_PHONE_STATE)

        val binder = service.onBind(null) as INodePluginService

        val corruptConfigJson = "INVALID_CORRUPT_JSON"
        val corruptInputJson = "INVALID_CORRUPT_JSON"

        val resultJson = binder.executeNode("node_corrupt", corruptConfigJson, corruptInputJson)
        assertNotNull(resultJson)

        val json = JSONObject(resultJson)
        assertTrue(json.has("isSuccess"))
    }
}
