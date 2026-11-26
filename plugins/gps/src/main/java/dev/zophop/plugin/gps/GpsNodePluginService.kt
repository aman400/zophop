package dev.zophop.plugin.gps

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.INodePluginService
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Action executor for [GET_LOCATION_ACTION].
 * Retrieves current device location (latitude, longitude, accuracy, maps link) and populates context variables.
 */
class GetLocationExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        if (appContext == null) {
            return NodeExecutionResult.Success(logMessage = "GPS action executed")
        }
        val failure = checkLocationPrerequisites(appContext)
        return if (failure != null) {
            NodeExecutionResult.Failed(failure)
        } else {
            populateLocation(context, appContext)
        }
    }
}

/**
 * Legacy executor retained for backward compatibility with pre-existing stored workflows.
 * Note: New check gatekeepers should be configured as [Precondition.GpsCheck].
 */
class GpsCheckExecutor : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        if (appContext == null) {
            return NodeExecutionResult.Success(logMessage = "Location services are active")
        }
        val failureReason = checkGpsStatus(node, appContext)
        return if (failureReason != null) {
            NodeExecutionResult.Failed(failureReason)
        } else {
            NodeExecutionResult.Success(logMessage = "Location services are active")
        }
    }
}

private fun hasLocationPermission(appContext: Context): Boolean =
    ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

private fun checkLocationPrerequisites(appContext: Context): String? =
    when {
        !hasLocationPermission(appContext) ->
            "Location permissions (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION) not granted"
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager == null ->
            "LocationManager unavailable"
        else -> null
    }

@android.annotation.SuppressLint("MissingPermission")
private fun populateLocation(
    context: ExecutionContext,
    appContext: Context,
): NodeExecutionResult {
    val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return NodeExecutionResult.Failed("LocationManager unavailable")
    val location =
        try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        } catch (_: SecurityException) {
            null
        }

    val lat = location?.latitude?.toString() ?: "0.0"
    val lon = location?.longitude?.toString() ?: "0.0"
    val acc = location?.accuracy?.toString() ?: "0.0"
    val mapsUrl = "https://maps.google.com/?q=$lat,$lon"

    context.set("latitude", lat)
    context.set("longitude", lon)
    context.set("accuracy", acc)
    context.set("maps_url", mapsUrl)

    return NodeExecutionResult.Success(logMessage = "Retrieved location: ($lat, $lon)")
}

private fun checkGpsStatus(
    node: WorkflowNode,
    appContext: Context,
): String? {
    val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val isGpsEnabled = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    val isNetworkEnabled = lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

    val config = parseJsonObjectSafe(node.configJson)
    val requireHighAccuracy = config.optString("requireHighAccuracy", "true") == "true"

    return when {
        !hasLocationPermission(appContext) ->
            "Location permissions (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION) not granted in GPS Plugin App"
        lm == null ->
            "LocationManager unavailable"
        !isGpsEnabled && !isNetworkEnabled ->
            "Device Location / GPS is turned off"
        requireHighAccuracy && !isGpsEnabled ->
            "High Accuracy GPS is required but only Network location is enabled"
        else ->
            null
    }
}

object GpsPluginModule : NodePluginModule {
    override val moduleId: String = "gps-plugin-module"

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? =
        when {
            typeId.contains("GET_LOCATION_ACTION") -> GetLocationExecutor()
            typeId.contains("GPS_CHECK_LOGIC") -> GpsCheckExecutor()
            else -> null
        }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = "dev.zophop.plugin.gps.GET_LOCATION_ACTION",
                displayName = "Get Current Location",
                description = "Fetches current device GPS coordinates (latitude, longitude, accuracy) and Google Maps link",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_gps",
                requiredPermissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                setupActivity = "dev.zophop.plugin.gps.SetupActivity",
                outputVariables = listOf("latitude", "longitude", "accuracy", "maps_url"),
                defaultConfigJson = """{"requireHighAccuracy":"true"}""",
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

open class GpsNodePluginService : Service() {
    private val binder =
        object : INodePluginService.Stub() {
            override fun getPluginApiVersion(): Int = 1

            override fun getManifests(): List<NodePluginManifest> = GpsPluginModule.getManifests()

            @android.annotation.SuppressLint("MissingPermission")
            override fun executeNode(
                nodeId: String,
                configJson: String,
                inputContextJson: String,
            ): String {
                val hasLocationPerm =
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                val locationManager =
                    if (hasLocationPerm) getSystemService(Context.LOCATION_SERVICE) as? LocationManager else null

                return when {
                    !hasLocationPerm ->
                        fail("Location permissions (ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION) not granted in GPS Plugin App")
                    locationManager == null ->
                        fail("LocationManager unavailable")
                    else -> {
                        val location =
                            try {
                                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                    ?: locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                            } catch (_: SecurityException) {
                                null
                            }

                        val lat = location?.latitude?.toString() ?: "0.0"
                        val lon = location?.longitude?.toString() ?: "0.0"
                        val acc = location?.accuracy?.toString() ?: "0.0"
                        val mapsUrl = "https://maps.google.com/?q=$lat,$lon"

                        val outputMap =
                            buildJsonObject {
                                put("latitude", lat)
                                put("longitude", lon)
                                put("accuracy", acc)
                                put("maps_url", mapsUrl)
                            }

                        buildJsonObject {
                            put("isSuccess", true)
                            put("outputPort", "main")
                            put("logMessage", "Location: ($lat, $lon)")
                            put("outputDataMap", outputMap)
                            put("latitude", lat)
                            put("longitude", lon)
                            put("accuracy", acc)
                            put("maps_url", mapsUrl)
                        }.toString()
                    }
                }
            }

            private fun fail(reason: String) =
                buildJsonObject {
                    put("isSuccess", false)
                    put("errorReason", reason)
                }.toString()
        }

    override fun onBind(intent: Intent?): IBinder = binder
}
