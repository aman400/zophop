package dev.zophop.plugin.gps.gpsplugin

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.plugin.api.INodePluginService
import org.json.JSONObject
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
class GpsNodePluginServiceTest {
    @Test
    fun testGpsPluginManifestStructure() {
        val serviceController = Robolectric.buildService(GpsNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val locAction = manifests.first()

        assertEquals("dev.zophop.gpsplugin.GET_LOCATION_ACTION", locAction.typeId)
        assertTrue(locAction.requiredPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals("dev.zophop.gpsplugin.SetupActivity", locAction.setupActivity)
    }

    @Test
    fun testGpsCheckFailsWhenPermissionMissing() {
        val serviceController = Robolectric.buildService(GpsNodePluginService::class.java).create()
        val service = serviceController.get()
        Shadows.shadowOf(service).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val binder = service.onBind(null) as INodePluginService

        val configJson = """{"typeId":"dev.zophop.gpsplugin.GET_LOCATION_ACTION"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = JSONObject(resultJson)
        assertFalse(json.getBoolean("isSuccess"))
        assertTrue(json.getString("errorReason").contains("Location permissions"))
    }
}
