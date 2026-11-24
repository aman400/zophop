package dev.zophop.plugin.ntfy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
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
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val ntfyAction = manifests.first()

        assertEquals("dev.zophop.plugin.ntfy.NTFY_ACTION", ntfyAction.typeId)
        assertEquals("NTFY_AUTH", ntfyAction.requiredCredentialType)
        assertTrue(ntfyAction.requiredPermissions.isEmpty())
        assertEquals(7, ntfyAction.configSchema.size)
        assertTrue(ntfyAction.configSchema.any { it.key == "token" })
    }

    @Test
    fun testNtfyActionFailsOnEmptyServerUrl() {
        val serviceController = Robolectric.buildService(NtfyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.ntfy.NTFY_ACTION"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = parseJsonObjectSafe(resultJson)
        assertEquals(false, json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("serverUrl is empty"))
    }

    @Test
    fun testNtfyActionWithEmojiHeadersDoesNotThrowIllegalArgumentException() {
        val serviceController = Robolectric.buildService(NtfyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{
            "typeId":"dev.zophop.plugin.ntfy.NTFY_ACTION",
            "serverUrl":"http://127.0.0.1:1",
            "topic":"test-topic",
            "title":"🚨 Emergency Alert ₹500",
            "tags":"fire,🔥"
        }"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")
        val json = parseJsonObjectSafe(resultJson)

        // It must NOT crash with OkHttp "Unexpected char" header exception
        val reason = json.optString("errorReason", "")
        org.junit.Assert.assertFalse(
            "Must not throw OkHttp header Unexpected char exception for emoji headers: $reason",
            reason.contains("Unexpected char", ignoreCase = true),
        )
    }
}
