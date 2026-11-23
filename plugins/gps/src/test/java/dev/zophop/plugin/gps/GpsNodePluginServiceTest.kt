package dev.zophop.plugin.gps

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
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
class GpsNodePluginServiceTest {
    @Test
    fun testGpsPluginManifestStructure() {
        val serviceController = Robolectric.buildService(GpsNodePluginService::class.java).create()
        val service = serviceController.get()
        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService
        val manifests = binder.getManifests()

        assertEquals(1, manifests.size)
        val locAction = manifests.first()

        assertEquals("dev.zophop.plugin.gps.GET_LOCATION_ACTION", locAction.typeId)
        assertTrue(locAction.requiredPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals("dev.zophop.plugin.gps.SetupActivity", locAction.setupActivity)
    }

    @Test
    fun testGpsPluginModuleCreateExecutor() {
        val okHttpClient = okhttp3.OkHttpClient()
        val executor = GpsPluginModule.createExecutor("dev.zophop.plugin.gps.GET_LOCATION_ACTION", okHttpClient, null)
        assertNotNull(executor)
        assertTrue(executor is GetLocationExecutor)

        // Backward compatibility executor for legacy GPS check
        val legacyExecutor = GpsPluginModule.createExecutor("dev.zophop.plugin.gps.GPS_CHECK_LOGIC", okHttpClient, null)
        assertNotNull(legacyExecutor)
        assertTrue(legacyExecutor is GpsCheckExecutor)
    }

    @Test
    fun testGpsCheckExecutorFailsWhenPermissionMissing() =
        runTest {
            val serviceController = Robolectric.buildService(GpsNodePluginService::class.java).create()
            val service = serviceController.get()
            Shadows.shadowOf(service).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

            val executor = GpsCheckExecutor()
            val node =
                WorkflowNode(
                    id = "g1",
                    name = "GPS Check",
                    type = "dev.zophop.plugin.gps.GPS_CHECK_LOGIC",
                    configJson = """{"requireHighAccuracy":"false"}""",
                )
            val result = executor.execute(node, ExecutionContext(), service)

            assertTrue(result is NodeExecutionResult.Failed)
            assertTrue((result as NodeExecutionResult.Failed).reason.contains("Location permissions"))
        }

    @Test
    fun testGpsCheckFailsWhenPermissionMissing() {
        val serviceController = Robolectric.buildService(GpsNodePluginService::class.java).create()
        val service = serviceController.get()
        Shadows.shadowOf(service).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val binder = service.onBind(null) as dev.zophop.plugin.api.INodePluginService

        val configJson = """{"typeId":"dev.zophop.plugin.gps.GPS_CHECK_LOGIC"}"""
        val resultJson = binder.executeNode("node_1", configJson, "{}")

        val json = JSONObject(resultJson)
        assertFalse(json.getBoolean("isSuccess"))
        assertTrue(json.getString("errorReason").contains("Location permissions"))
    }
}
