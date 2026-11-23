package dev.zophop.ui.workflows

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationImportant
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.PreconditionType
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import org.json.JSONArray
import org.json.JSONObject

data class PermissionInstructionData(
    val title: String,
    val message: String,
    val onOpenSettings: () -> Unit,
)

object WorkflowUiHelper {
    fun getNodeTitle(type: String): String {
        val manifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(type)
        if (manifest != null) return manifest.displayName
        return type.substringAfterLast(".").replace("_", " ")
    }

    fun getDefaultConfigForType(type: String): String =
        when {
            type.contains("INTERNET_CHECK", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("requireInternet", "true")
                        put("requireConnected", "true")
                        put("requiredNetworkType", "ANY")
                    }.toString()
            type.contains("VPN_CHECK", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("requireConnected", "true")
                    }.toString()
            type.contains("GPS_CHECK", ignoreCase = true) || type.contains("GET_LOCATION_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("requireGpsEnabled", "true")
                        put("fetchCoordinates", "true")
                        put("requireHighAccuracy", "true")
                    }.toString()
            type.contains("BATTERY_CHECK", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("minBatteryPercent", "15")
                        put("requireCharging", "false")
                    }.toString()
            type.contains("SIM_CHECK", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("requireSimReady", "true")
                    }.toString()
            type.contains("BLUETOOTH_CHECK", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("requireBluetoothEnabled", "true")
                        put("requireConnected", "false")
                    }.toString()
            type.contains("CONDITION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("variable", "status")
                        put("operator", "EQUALS")
                        put("value", "active")
                    }.toString()
            type.contains("HTTP_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("url", "https://example.com/health")
                        put("method", "GET")
                    }.toString()
            type.contains("PARSE_NOTIFICATION", ignoreCase = true) || type.contains("REGEX_PARSER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("regex", "\\b\\d{6}\\b")
                        put("outputKey", "code")
                    }.toString()
            type.contains("JSON_PARSER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("jsonKey", "data.status")
                        put("outputKey", "parsedStatus")
                        put("targetType", "AUTO")
                    }.toString()
            type.contains("TEMPLATE_FORMATTER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("template", "\${body}")
                    }.toString()
            type.contains("SEND_NOTIFICATION_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("title", "ZopHop Notification")
                        put("body", "\${body}")
                    }.toString()
            type.contains("SEND_SMS_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("recipient", "")
                        put("body", "\${body}")
                    }.toString()
            type.contains("CRON_TRIGGER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("cronExpression", "every 30m")
                    }.toString()
            type.contains("MANUAL_TRIGGER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("testBody", "{\"status\":\"success\",\"message\":\"Manual test execution\"}")
                    }.toString()
            type.contains("SMS_TRIGGER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("allowlist", ".*")
                        put("keywordRegex", ".*")
                    }.toString()
            type.contains("NOTIFICATION_TRIGGER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("packageFilter", ".*")
                        put("textRegex", ".*")
                    }.toString()
            type.contains("CALL_TRIGGER", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("callerRegex", ".*")
                    }.toString()
            type.contains("NTFY_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("serverUrl", "https://ntfy.sh")
                        put("topic", "my-topic")
                        put("priority", "3")
                        put("title", "ZopHop Notification")
                        put("template", "\${body}")
                    }.toString()
            type.contains("GOTIFY_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("serverUrl", "https://gotify.example.com")
                        put("priority", "5")
                        put("title", "ZopHop Notification")
                        put("template", "\${body}")
                    }.toString()
            type.contains("TELEGRAM_ACTION", ignoreCase = true) ->
                JSONObject()
                    .apply {
                        put("messageTemplate", "\${body}")
                    }.toString()
            else -> "{}"
        }

    fun getNodeDescription(type: String): String {
        val manifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(type)
        if (manifest != null) return manifest.description
        return "Plugin provided workflow step ($type)"
    }

    fun isConditionalNode(
        nodeType: String,
        manifest: NodePluginManifest? = null,
    ): Boolean {
        if (manifest?.category == NodeCategory.LOGIC) return true
        val upper = nodeType.uppercase()
        return upper.contains("CONDITION") ||
            upper.contains("CHECK") ||
            upper == NodeType.CONDITION ||
            upper == NodeType.VPN_CHECK ||
            upper == NodeType.GPS_CHECK ||
            upper == NodeType.INTERNET_CHECK ||
            upper == NodeType.BLUETOOTH_CHECK ||
            upper == NodeType.SIM_CHECK ||
            upper == NodeType.BATTERY_CHECK
    }

    fun getNodeIcon(type: String): ImageVector =
        when {
            type.contains("SMS_TRIGGER", ignoreCase = true) -> Icons.Default.Call
            type.contains("CALL_TRIGGER", ignoreCase = true) -> Icons.Default.Call
            type.contains("ANSWER_CALL_ACTION", ignoreCase = true) -> Icons.Default.Call
            type.contains("DISCONNECT_CALL_ACTION", ignoreCase = true) -> Icons.Default.Call
            type.contains("NOTIFICATION_TRIGGER", ignoreCase = true) -> Icons.Default.Notifications
            type.contains("CRON_TRIGGER", ignoreCase = true) -> Icons.Default.Schedule
            type.contains("MANUAL_TRIGGER", ignoreCase = true) -> Icons.Default.PlayArrow
            type.contains("PARSE_NOTIFICATION", ignoreCase = true) -> Icons.Default.Transform
            type.contains("REGEX_PARSER", ignoreCase = true) -> Icons.Default.Code
            type.contains("JSON_PARSER", ignoreCase = true) -> Icons.Default.DataObject
            type.contains("TEMPLATE_FORMATTER", ignoreCase = true) -> Icons.Default.Transform
            type.contains("CONDITION", ignoreCase = true) -> Icons.AutoMirrored.Filled.Rule
            type.contains("VPN_CHECK", ignoreCase = true) -> Icons.Default.VpnKey
            type.contains("GPS_CHECK", ignoreCase = true) || type.contains("LOCATION", ignoreCase = true) -> Icons.Default.LocationOn
            type.contains("INTERNET_CHECK", ignoreCase = true) -> Icons.Default.Wifi
            type.contains("BLUETOOTH_CHECK", ignoreCase = true) -> Icons.Default.Bluetooth
            type.contains("SIM_CHECK", ignoreCase = true) -> Icons.Default.PhoneAndroid
            type.contains("BATTERY_CHECK", ignoreCase = true) -> Icons.Default.BatteryChargingFull
            type.contains("NTFY_ACTION", ignoreCase = true) -> Icons.Default.DeviceHub
            type.contains("GOTIFY_ACTION", ignoreCase = true) -> Icons.Default.NotificationImportant
            type.contains("HTTP_ACTION", ignoreCase = true) -> Icons.Default.Http
            type.contains("SEND_NOTIFICATION_ACTION", ignoreCase = true) -> Icons.Default.Notifications
            type.contains("SEND_SMS_ACTION", ignoreCase = true) -> Icons.Default.Sms
            else -> Icons.Default.Extension
        }

    fun getPreconditionTitle(type: String): String =
        when (type) {
            PreconditionType.INTERNET_CHECK.name -> "Network Internet Check"
            PreconditionType.VPN_CHECK.name -> "VPN Connection Check"
            PreconditionType.HEALTH_CHECK.name -> "HTTP Endpoint Healthcheck"
            PreconditionType.SIM_CHECK.name -> "SIM Card Ready Check"
            PreconditionType.GPS_CHECK.name -> "GPS Location Service Check"
            PreconditionType.BATTERY_CHECK.name -> "Battery Level & Charging Check"
            PreconditionType.CHAINED_CHECK.name -> "Chained Pipeline"
            else -> type
        }

    fun getPreconditionIcon(type: String): ImageVector =
        when (type) {
            PreconditionType.INTERNET_CHECK.name -> Icons.Default.Wifi
            PreconditionType.VPN_CHECK.name -> Icons.Default.VpnKey
            PreconditionType.HEALTH_CHECK.name -> Icons.Default.LocalHospital
            PreconditionType.SIM_CHECK.name -> Icons.Default.PhoneAndroid
            PreconditionType.GPS_CHECK.name -> Icons.Default.LocationOn
            PreconditionType.BATTERY_CHECK.name -> Icons.Default.BatteryChargingFull
            PreconditionType.CHAINED_CHECK.name -> Icons.Default.DeviceHub
            else -> Icons.Default.Security
        }

    fun getPreconditionPipelineSummary(
        type: String,
        pipelineJson: String,
    ): Pair<ImageVector, String> {
        if (type == PreconditionType.CHAINED_CHECK.name && pipelineJson.isNotBlank()) {
            try {
                val parsed = parseChainedPipelineSummary(pipelineJson)
                if (parsed != null) return parsed
            } catch (ignored: Exception) {
            }
        }
        return getPreconditionIcon(type) to getPreconditionTitle(type)
    }

    private fun parseChainedPipelineSummary(pipelineJson: String): Pair<ImageVector, String>? {
        val json = JSONObject(pipelineJson)
        val nodesArr = json.optJSONArray("nodes") ?: JSONArray()
        return when {
            nodesArr.length() == 1 -> {
                val nodeObj = nodesArr.getJSONObject(0)
                val nodeType = nodeObj.optString("type", "")
                val nodeName = nodeObj.optString("name", getNodeTitle(nodeType))
                getNodeIcon(nodeType) to "$nodeName (1 Step)"
            }
            nodesArr.length() > 1 -> {
                val names = (0 until nodesArr.length()).map { i ->
                    val n = nodesArr.getJSONObject(i)
                    n.optString("name", getNodeTitle(n.optString("type", "")))
                }
                Icons.Default.DeviceHub to "Chained Pipeline (${nodesArr.length()} Steps: ${names.joinToString(" → ")})"
            }
            else -> null
        }
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val pkg = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(pkg)
    }

    fun openAppSettings(
        context: Context,
        packageName: String? = null,
    ) {
        val targetPkg = packageName ?: context.packageName
        try {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", targetPkg, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent =
                    Intent(Settings.ACTION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent =
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    fun getRequiredPermissions(
        nodeType: String,
        manifest: NodePluginManifest? = null,
    ): List<String> {
        val perms = mutableListOf<String>()
        when {
            nodeType.contains("NOTIFICATION_TRIGGER", ignoreCase = true) -> {
                perms.add("NOTIFICATION_LISTENER")
            }
            nodeType.contains("SMS_TRIGGER", ignoreCase = true) -> {
                perms.add(Manifest.permission.RECEIVE_SMS)
            }
            nodeType.contains("SEND_SMS_ACTION", ignoreCase = true) -> {
                perms.add(Manifest.permission.SEND_SMS)
            }
            nodeType.contains("SEND_NOTIFICATION_ACTION", ignoreCase = true) -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            nodeType.contains("GPS_CHECK", ignoreCase = true) || nodeType.contains("GET_LOCATION", ignoreCase = true) -> {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            nodeType.contains("BLUETOOTH_CHECK", ignoreCase = true) -> {
                if (Build.VERSION.SDK_INT >= 31) {
                    perms.add("android.permission.BLUETOOTH_CONNECT")
                } else {
                    perms.add(Manifest.permission.BLUETOOTH)
                }
            }
            nodeType.contains("SIM_CHECK", ignoreCase = true) || nodeType.contains("CALL_TRIGGER", ignoreCase = true) -> {
                perms.add(Manifest.permission.READ_PHONE_STATE)
            }
            nodeType.contains(
                "ANSWER_CALL_ACTION",
                ignoreCase = true,
            ) ||
                nodeType.contains("DISCONNECT_CALL_ACTION", ignoreCase = true) -> {
                perms.add(Manifest.permission.ANSWER_PHONE_CALLS)
            }
        }
        manifest?.requiredPermissions?.let {
            perms.addAll(it)
        }
        return perms.distinct()
    }

    fun getMissingPermissions(
        context: Context,
        nodeType: String,
        manifest: NodePluginManifest? = null,
        pluginPackage: String? = null,
    ): List<String> {
        val required = getRequiredPermissions(nodeType, manifest)
        val missing =
            required.filter { perm ->
                if (perm == "NOTIFICATION_LISTENER") {
                    !isNotificationListenerEnabled(context)
                } else if (pluginPackage != null) {
                    context.packageManager.checkPermission(perm, pluginPackage) != PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
                }
            }
        if (missing.contains(Manifest.permission.ACCESS_FINE_LOCATION) && missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val hasFine =
                if (pluginPackage != null) {
                    context.packageManager.checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, pluginPackage) ==
                        PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                }
            val hasCoarse =
                if (pluginPackage != null) {
                    context.packageManager.checkPermission(Manifest.permission.ACCESS_COARSE_LOCATION, pluginPackage) ==
                        PackageManager.PERMISSION_GRANTED
                } else {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
                }
            if (hasFine || hasCoarse) {
                return missing.filterNot {
                    it == Manifest.permission.ACCESS_FINE_LOCATION ||
                        it == Manifest.permission.ACCESS_COARSE_LOCATION
                }
            }
        }
        return missing
    }

    fun isPermissionMissing(
        context: Context,
        nodeType: String,
        manifest: NodePluginManifest? = null,
        pluginPackage: String? = null,
    ): Boolean = getMissingPermissions(context, nodeType, manifest, pluginPackage).isNotEmpty()

    fun getMissingPermissionSummary(
        context: Context,
        nodeType: String,
        manifest: NodePluginManifest? = null,
        pluginPackage: String? = null,
    ): String? {
        val missing = getMissingPermissions(context, nodeType, manifest, pluginPackage)
        if (missing.isEmpty()) return null

        return when {
            missing.contains("NOTIFICATION_LISTENER") -> "Notification Access required"
            missing.contains(Manifest.permission.RECEIVE_SMS) -> "SMS permission required"
            missing.contains(Manifest.permission.SEND_SMS) -> "Send SMS permission required"
            Build.VERSION.SDK_INT >= 33 && missing.contains(Manifest.permission.POST_NOTIFICATIONS) -> "Notification permission required"
            missing.contains(
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) ||
                missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION) -> "Location permission required"
            missing.contains(
                "android.permission.BLUETOOTH_CONNECT",
            ) ||
                missing.contains(Manifest.permission.BLUETOOTH) -> "Bluetooth permission required"
            missing.contains(Manifest.permission.READ_PHONE_STATE) -> "Phone permission required"
            missing.contains(Manifest.permission.ANSWER_PHONE_CALLS) -> "Call control permission required"
            pluginPackage != null -> "Plugin permission required"
            else -> "${missing.map { it.substringAfterLast('.') }.firstOrNull()?.replace('_', ' ') ?: "Permission"} required"
        }
    }

    fun getMissingPermissionBanner(
        context: Context,
        nodeType: String,
        manifest: NodePluginManifest? = null,
        pluginPackage: String? = null,
    ): Pair<String, String>? {
        val missing = getMissingPermissions(context, nodeType, manifest, pluginPackage)
        if (missing.isEmpty()) return null

        return when {
            missing.contains("NOTIFICATION_LISTENER") ->
                Pair(
                    "⚠️ Notification Access Missing",
                    "Workflow cannot intercept notifications until Notification Access is enabled in settings.",
                )
            missing.contains(Manifest.permission.RECEIVE_SMS) ->
                Pair(
                    "⚠️ SMS Permission Missing (RECEIVE_SMS)",
                    "Workflow cannot intercept incoming text messages without SMS permission.",
                )
            missing.contains(Manifest.permission.SEND_SMS) ->
                Pair(
                    "⚠️ Send SMS Permission Missing (SEND_SMS)",
                    "Sending text messages requires SEND_SMS permission.",
                )
            Build.VERSION.SDK_INT >= 33 && missing.contains(Manifest.permission.POST_NOTIFICATIONS) ->
                Pair(
                    "⚠️ Notification Permission Missing (POST_NOTIFICATIONS)",
                    "Displaying notifications requires POST_NOTIFICATIONS permission.",
                )
            missing.contains(Manifest.permission.ACCESS_FINE_LOCATION) || missing.contains(Manifest.permission.ACCESS_COARSE_LOCATION) ->
                Pair(
                    "⚠️ Location Permission Missing (ACCESS_FINE_LOCATION)",
                    "Retrieving GPS coordinates and status requires Location permission.",
                )
            missing.contains("android.permission.BLUETOOTH_CONNECT") || missing.contains(Manifest.permission.BLUETOOTH) ->
                Pair(
                    "⚠️ Bluetooth Permission Missing",
                    "Inspecting Bluetooth status and connected devices requires Bluetooth permission.",
                )
            missing.contains(Manifest.permission.READ_PHONE_STATE) ->
                Pair(
                    "⚠️ Phone Permission Missing (READ_PHONE_STATE)",
                    "Inspecting SIM card / incoming call status requires READ_PHONE_STATE permission.",
                )
            missing.contains(Manifest.permission.ANSWER_PHONE_CALLS) ->
                Pair(
                    "⚠️ Call Control Permission Missing (ANSWER_PHONE_CALLS)",
                    "Answering or rejecting phone calls requires Call Control permission.",
                )
            pluginPackage != null ->
                Pair(
                    "⚠️ Plugin Permission Missing",
                    "Node requires ${missing.joinToString(", ") { it.substringAfterLast('.') }}. Grant in Plugin App.",
                )
            else ->
                Pair(
                    "⚠️ Permission Missing",
                    "This node requires ${missing.joinToString(", ") { it.substringAfterLast('.') }}. Grant permission to enable.",
                )
        }
    }

    fun getPermissionInstruction(
        context: Context,
        missingPermissions: List<String>,
        nodeType: String? = null,
        pluginPackage: String? = null,
        setupActivity: String? = null,
    ): PermissionInstructionData {
        val isPhonePerm = missingPermissions.any { it.contains("PHONE", ignoreCase = true) || it.contains("CALL", ignoreCase = true) }
        val isPhoneNode = nodeType?.let { it.contains("CALL", ignoreCase = true) || it.contains("SIM_CHECK", ignoreCase = true) } == true

        return when {
            missingPermissions.contains("NOTIFICATION_LISTENER") || nodeType?.contains("NOTIFICATION_TRIGGER", ignoreCase = true) == true -> {
                PermissionInstructionData(
                    title = "Notification Access Required",
                    message =
                        "To detect and intercept notifications, Android requires Notification Access to be enabled manually in system settings:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. In 'Device & app notifications', find and select 'ZopHop'.\n" +
                            "3. Turn the 'Allow notification access' switch ON.\n" +
                            "4. Tap 'Allow' in the confirmation prompt.\n" +
                            "5. Return to ZopHop to continue.",
                    onOpenSettings = { openNotificationListenerSettings(context) },
                )
            }
            missingPermissions.any { it.contains("LOCATION", ignoreCase = true) } ||
                nodeType?.contains("GPS_CHECK", ignoreCase = true) == true ||
                nodeType?.contains("GET_LOCATION", ignoreCase = true) == true -> {
                PermissionInstructionData(
                    title = "Location Permission Required",
                    message =
                        "To check GPS status and retrieve coordinates, Location permission is required:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Tap 'Permissions'.\n" +
                            "3. Select 'Location'.\n" +
                            "4. Choose 'Allow only while using the app' and enable 'Use precise location'.\n" +
                            "5. Return to ZopHop to continue.",
                    onOpenSettings = { openAppSettings(context, pluginPackage) },
                )
            }
            missingPermissions.any { it.contains("SMS", ignoreCase = true) } ||
                nodeType?.contains("SMS", ignoreCase = true) == true -> {
                PermissionInstructionData(
                    title = "SMS Permission Required",
                    message =
                        "To intercept or send text messages, SMS permission is required:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Tap 'Permissions'.\n" +
                            "3. Select 'SMS'.\n" +
                            "4. Choose 'Allow'.\n" +
                            "5. Return to ZopHop to continue.",
                    onOpenSettings = { openAppSettings(context, pluginPackage) },
                )
            }
            isPhonePerm || isPhoneNode -> {
                PermissionInstructionData(
                    title = "Phone & Call Permission Required",
                    message =
                        "To inspect SIM card status, detect phone calls, or manage calls, Phone permission is required:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Tap 'Permissions'.\n" +
                            "3. Select 'Phone' (or 'Call logs').\n" +
                            "4. Choose 'Allow'.\n" +
                            "5. Return to ZopHop to continue.",
                    onOpenSettings = { openAppSettings(context, pluginPackage) },
                )
            }
            missingPermissions.any { it.contains("BLUETOOTH", ignoreCase = true) } ||
                nodeType?.contains("BLUETOOTH", ignoreCase = true) == true -> {
                PermissionInstructionData(
                    title = "Nearby Devices Permission Required",
                    message =
                        "To check Bluetooth status and discover connected devices, Nearby Devices permission is required:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Tap 'Permissions'.\n" +
                            "3. Select 'Nearby devices'.\n" +
                            "4. Choose 'Allow'.\n" +
                            "5. Return to ZopHop to continue.",
                    onOpenSettings = { openAppSettings(context, pluginPackage) },
                )
            }
            missingPermissions.any { it.contains("POST_NOTIFICATIONS", ignoreCase = true) } ||
                nodeType?.contains("SEND_NOTIFICATION_ACTION", ignoreCase = true) == true -> {
                PermissionInstructionData(
                    title = "Notification Permission Required",
                    message =
                        "To display alerts and status notifications, Notification permission is required:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Tap 'Notifications'.\n" +
                            "3. Turn the 'All ZopHop notifications' switch ON.\n" +
                            "4. Return to ZopHop to continue.",
                    onOpenSettings = { openAppSettings(context, pluginPackage) },
                )
            }
            pluginPackage != null -> {
                PermissionInstructionData(
                    title = "Plugin Permission Required",
                    message =
                        "This plugin requires permissions to operate:\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Follow the setup instructions in the plugin application.\n" +
                            "3. Return to ZopHop to continue.",
                    onOpenSettings = {
                        if (setupActivity != null) {
                            try {
                                val intent =
                                    Intent().apply {
                                        component = android.content.ComponentName(pluginPackage, setupActivity)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                context.startActivity(intent)
                                return@PermissionInstructionData
                            } catch (_: Exception) {
                            }
                        }
                        openAppSettings(context, pluginPackage)
                    },
                )
            }
            else -> {
                val permNames = missingPermissions.map { it.substringAfterLast('.') }.filter { it.isNotBlank() }.joinToString(", ")
                PermissionInstructionData(
                    title = "Permission Required",
                    message =
                        "This step requires additional permissions" + (if (permNames.isNotBlank()) " ($permNames)" else "") + ":\n\n" +
                            "1. Tap 'Open Settings' below.\n" +
                            "2. Tap 'Permissions'.\n" +
                            "3. Grant the required permissions.\n" +
                            "4. Return to ZopHop to continue.",
                    onOpenSettings = { openAppSettings(context, pluginPackage) },
                )
            }
        }
    }

    @Composable
    fun PermissionInstructionDialog(
        data: PermissionInstructionData,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = data.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = data.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismiss()
                        data.onOpenSettings()
                    },
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
        )
    }
}
