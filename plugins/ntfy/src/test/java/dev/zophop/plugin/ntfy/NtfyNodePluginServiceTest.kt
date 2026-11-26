package dev.zophop.plugin.ntfy

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.optBoolean
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(
            "Must not throw OkHttp header Unexpected char exception for emoji headers: $reason",
            reason.contains("Unexpected char", ignoreCase = true),
        )
    }

    @Test
    fun testNtfyActionWithCrlfInHeadersAndTokenDoesNotCrashWithUnexpectedChar() {
        val serviceController = Robolectric.buildService(NtfyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{
            "typeId":"dev.zophop.plugin.ntfy.NTFY_ACTION",
            "serverUrl":"http://127.0.0.1:1",
            "topic":"test-topic",
            "title":"Alert\r\nInjected: bad",
            "priority":"3\r\n",
            "tags":"tag1\n",
            "token":"tok_abc\r\n"
        }"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")
        val json = parseJsonObjectSafe(resultJson)

        val reason = json.optString("errorReason", "")
        assertFalse(
            "Must strip CR/LF before header construction: $reason",
            reason.contains("Unexpected char", ignoreCase = true),
        )
        assertFalse(
            "Error reason must redact token: $reason",
            reason.contains("tok_abc"),
        )
    }

    @Test
    fun testNtfyActionExecutor_redactsTokenFromIoExceptionAndErrorResponse() =
        runTest {
            val client =
                okhttp3.OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        okhttp3.Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body("Denied for token tk_secret_ntfy_777".toResponseBody())
                            .build()
                    }.build()

            val executor = NtfyActionExecutor(okHttpClient = client)
            val node =
                dev.zophop.domain.model.WorkflowNode(
                    id = "n1",
                    name = "ntfy",
                    type = "dev.zophop.plugin.ntfy.NTFY_ACTION",
                    configJson = """{"serverUrl":"https://ntfy.sh","topic":"alerts","token":"tk_secret_ntfy_777","template":"hi"}""",
                )
            val result = executor.execute(node, dev.zophop.plugin.api.ExecutionContext(), null)
            assertTrue(result is dev.zophop.plugin.api.NodeExecutionResult.Failed)
            val reason = (result as dev.zophop.plugin.api.NodeExecutionResult.Failed).reason
            assertFalse("Failed log must redact token", reason.contains("tk_secret_ntfy_777"))
        }
}
