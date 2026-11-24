package dev.zophop.plugin.ntfy.ntfyplugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.plugin.api.INodePluginService
import org.json.JSONObject
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class NtfyNodePluginServiceTest {
    @Test
    fun testNtfyPluginManifestStructure() {
        val serviceController = Robolectric.buildService(NtfyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val ntfyAction = manifests.first()

        assertEquals("dev.zophop.ntfyplugin.NTFY_ACTION", ntfyAction.typeId)
        assertEquals("NTFY_AUTH", ntfyAction.requiredCredentialType)
        assertTrue(ntfyAction.requiredPermissions.isEmpty())
        assertEquals(7, ntfyAction.configSchema.size)
        assertTrue(ntfyAction.configSchema.any { it.key == "token" })
    }

    @Test
    fun testNtfyActionFailsOnEmptyServerUrl() {
        val serviceController = Robolectric.buildService(NtfyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.ntfyplugin.NTFY_ACTION"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = JSONObject(resultJson)
        assertEquals(false, json.getBoolean("isSuccess"))
        assertTrue(json.getString("errorReason").contains("serverUrl is empty"))
    }

    @Test
    fun testNtfyActionWithEmojiHeadersDoesNotThrowIllegalArgumentException() {
        val serviceController = Robolectric.buildService(NtfyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as INodePluginService

        val configJson = """{
            "typeId":"dev.zophop.ntfyplugin.NTFY_ACTION",
            "serverUrl":"http://127.0.0.1:1",
            "topic":"test-topic",
            "title":"🚨 Emergency Alert ₹500",
            "tags":"fire,🔥"
        }"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")
        val json = JSONObject(resultJson)

        val reason = json.optString("errorReason", "")
        Assert.assertFalse(
            "Must not throw OkHttp header Unexpected char exception for emoji headers: $reason",
            reason.contains("Unexpected char", ignoreCase = true),
        )
    }
}
