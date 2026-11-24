package dev.zophop.plugin.gotify

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
class GotifyNodePluginServiceTest {
    @Test
    fun testGotifyPluginManifestStructure() {
        val serviceController = Robolectric.buildService(GotifyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val gotifyAction = manifests.first()

        assertEquals("dev.zophop.plugin.gotify.GOTIFY_ACTION", gotifyAction.typeId)
        assertEquals("GOTIFY_AUTH", gotifyAction.requiredCredentialType)
        assertTrue(gotifyAction.requiredPermissions.isEmpty())
        assertEquals(5, gotifyAction.configSchema.size)
    }

    @Test
    fun testGotifyActionFailsOnEmptyServerUrl() {
        val serviceController = Robolectric.buildService(GotifyNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.gotify.GOTIFY_ACTION"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = parseJsonObjectSafe(resultJson)
        assertEquals(false, json.optBoolean("isSuccess"))
        assertTrue(json.optString("errorReason").contains("serverUrl is empty"))
    }
}
