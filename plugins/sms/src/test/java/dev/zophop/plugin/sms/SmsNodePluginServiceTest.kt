package dev.zophop.plugin.sms

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class SmsNodePluginServiceTest {
    @Test
    fun testSmsPluginManifestStructureAndPermissions() {
        val serviceController = Robolectric.buildService(SmsNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(2, manifests.size)

        val trig = manifests.find { it.typeId == "dev.zophop.plugin.sms.SMS_TRIGGER" }!!
        assertTrue(trig.requiredPermissions.contains(Manifest.permission.RECEIVE_SMS))

        val sendSms = manifests.find { it.typeId == "dev.zophop.plugin.sms.SEND_SMS_ACTION" }!!
        assertTrue(sendSms.requiredPermissions.contains(Manifest.permission.SEND_SMS))

        // SIM checks are Preconditions (Precondition.SimCheck), not workflow pipeline nodes
        val simCheckNode = manifests.find { it.typeId.contains("SIM_CHECK_LOGIC") }
        org.junit.Assert.assertNull("SIM Check must not be exposed as a workflow node; it belongs in Preconditions", simCheckNode)
    }

    @Test
    fun testSmsTriggerFailsWhenReceiveSmsPermissionMissing() {
        val serviceController = Robolectric.buildService(SmsNodePluginService::class.java).create()
        val service = serviceController.get()
        Shadows.shadowOf(service).denyPermissions(Manifest.permission.RECEIVE_SMS)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.sms.SMS_TRIGGER"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = parseJsonObjectSafe(resultJson)
        assertFalse(json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("RECEIVE_SMS"))
    }

    @Test
    fun testSendSmsActionFailsWhenSendSmsPermissionMissing() {
        val serviceController = Robolectric.buildService(SmsNodePluginService::class.java).create()
        val service = serviceController.get()
        Shadows.shadowOf(service).denyPermissions(Manifest.permission.SEND_SMS)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.sms.SEND_SMS_ACTION","recipient":"123","body":"test"}"""
        val resultJson = binder.executeNode("node_2", configJson, "{}")

        val json = parseJsonObjectSafe(resultJson)
        assertFalse(json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("SEND_SMS"))
    }
}
