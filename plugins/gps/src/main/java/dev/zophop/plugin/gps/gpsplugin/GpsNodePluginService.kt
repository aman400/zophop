package dev.zophop.plugin.gps.gpsplugin

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.IBinder
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import org.json.JSONObject

object GpsPluginModule : NodePluginModule {
    override val moduleId: String = "gps-plugin-module"

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.gpsplugin.GET_LOCATION_ACTION",
                displayName = "Get Current Location",
                description = "Fetches current device GPS coordinates (latitude, longitude, accuracy) and Google Maps link",
                category = NodeCategory.ACTION,
                requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                setupActivity = "dev.zophop.gpsplugin.SetupActivity",
                outputVariables = listOf("latitude", "longitude", "accuracy", "maps_url"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.DropdownField(
                            key = "requireHighAccuracy",
                            label = "Require High Accuracy (GPS)",
                            options =
                                listOf(
                                    "true" to "Yes (GPS Provider)",
                                    "false" to "No (Network/Cell Tower is fine)",
                                ),
                        ),
                    ),
            ),
        )
}

class GpsNodePluginService : Service() {
    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = GpsPluginModule.getManifests()

            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String {
                val hasLocationPerm =
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                if (!hasLocationPerm) {
                    return fail("Location permissions (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION) not granted in GPS Plugin App")
                }

                val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
                val location =
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

                val lat = location?.latitude?.toString() ?: "0.0"
                val lon = location?.longitude?.toString() ?: "0.0"
                val acc = location?.accuracy?.toString() ?: "0.0"
                val mapsUrl = "https://maps.google.com/?q=$lat,$lon"

                return JSONObject()
                    .apply {
                        put("isSuccess", true)
                        put("outputPort", "main")
                        put("logMessage", "Location: ($lat, $lon)")
                        put("latitude", lat)
                        put("longitude", lon)
                        put("accuracy", acc)
                        put("maps_url", mapsUrl)
                    }.toString()
            }

            private fun fail(reason: String) =
                JSONObject()
                    .apply {
                        put("isSuccess", false)
                        put("errorReason", reason)
                    }.toString()
        }

    override fun onBind(intent: Intent?): IBinder = binder
}
