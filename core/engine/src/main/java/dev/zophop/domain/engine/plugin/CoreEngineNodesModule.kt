package dev.zophop.domain.engine.plugin

import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.executors.BatteryCheckExecutor
import dev.zophop.domain.engine.executors.BluetoothCheckExecutor
import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.CronTriggerExecutor
import dev.zophop.domain.engine.executors.GpsCheckExecutor
import dev.zophop.domain.engine.executors.HttpActionExecutor
import dev.zophop.domain.engine.executors.InternetCheckExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.engine.executors.ManualTriggerExecutor
import dev.zophop.domain.engine.executors.RegexParserExecutor
import dev.zophop.domain.engine.executors.SendNotificationExecutor
import dev.zophop.domain.engine.executors.SimCheckExecutor
import dev.zophop.domain.engine.executors.TemplateFormatterExecutor
import dev.zophop.domain.engine.executors.VpnCheckExecutor
import dev.zophop.domain.model.CredentialType
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object CoreEngineNodesModule : NodePluginModule {
    override val moduleId: String = "core-engine-nodes"

    const val MANUAL_TRIGGER = "MANUAL_TRIGGER"
    const val CRON_TRIGGER = "CRON_TRIGGER"
    const val PARSE_NOTIFICATION = "PARSE_NOTIFICATION"
    const val REGEX_PARSER = "REGEX_PARSER"
    const val JSON_PARSER = "JSON_PARSER"
    const val TEMPLATE_FORMATTER = "TEMPLATE_FORMATTER"
    const val CONDITION = "CONDITION"
    const val HTTP_ACTION = "HTTP_ACTION"
    const val SEND_NOTIFICATION_ACTION = "SEND_NOTIFICATION_ACTION"

    // Condition Checks (Hardware, Network & Sensors)
    const val VPN_CHECK = "VPN_CHECK"
    const val GPS_CHECK = "GPS_CHECK"
    const val INTERNET_CHECK = "INTERNET_CHECK"
    const val BLUETOOTH_CHECK = "BLUETOOTH_CHECK"
    const val SIM_CHECK = "SIM_CHECK"
    const val BATTERY_CHECK = "BATTERY_CHECK"

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? {
        val credRepo = credentialRepository as? CredentialRepository
        return when {
            typeId == MANUAL_TRIGGER || typeId.endsWith(".$MANUAL_TRIGGER") -> ManualTriggerExecutor()
            typeId == CRON_TRIGGER || typeId.endsWith(".$CRON_TRIGGER") -> CronTriggerExecutor()
            typeId == PARSE_NOTIFICATION ||
                typeId.endsWith(".$PARSE_NOTIFICATION") ||
                typeId == REGEX_PARSER ||
                typeId.endsWith(".$REGEX_PARSER") -> RegexParserExecutor()
            typeId == JSON_PARSER || typeId.endsWith(".$JSON_PARSER") -> JsonParserExecutor()
            typeId == TEMPLATE_FORMATTER || typeId.endsWith(".$TEMPLATE_FORMATTER") -> TemplateFormatterExecutor()
            typeId == CONDITION || typeId.endsWith(".$CONDITION") -> ConditionExecutor()
            typeId == HTTP_ACTION || typeId.endsWith(".$HTTP_ACTION") -> HttpActionExecutor(okHttpClient, credRepo)
            typeId == SEND_NOTIFICATION_ACTION || typeId.endsWith(".$SEND_NOTIFICATION_ACTION") -> SendNotificationExecutor()
            typeId == VPN_CHECK || typeId.endsWith(".$VPN_CHECK") -> VpnCheckExecutor()
            typeId == GPS_CHECK || typeId.endsWith(".$GPS_CHECK") -> GpsCheckExecutor()
            typeId == INTERNET_CHECK || typeId.endsWith(".$INTERNET_CHECK") -> InternetCheckExecutor()
            typeId == BLUETOOTH_CHECK || typeId.endsWith(".$BLUETOOTH_CHECK") -> BluetoothCheckExecutor()
            typeId == SIM_CHECK || typeId.endsWith(".$SIM_CHECK") -> SimCheckExecutor()
            typeId == BATTERY_CHECK || typeId.endsWith(".$BATTERY_CHECK") -> BatteryCheckExecutor()
            else -> null
        }
    }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            // 1. Manual Trigger Node
            NodePluginManifest(
                typeId = MANUAL_TRIGGER,
                displayName = "Manual Action Trigger",
                description = "Triggers manually on-demand with test simulation payloads",
                category = NodeCategory.TRIGGER,
                iconResName = "ic_node_manual_trigger",
                outputVariables = listOf("body", "text", "timestamp"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.CodeTemplateField(
                            key = "testBody",
                            label = "Simulated Event Body",
                            minLines = 3,
                        ),
                    ),
            ),
            // 2. Cron Schedule Trigger
            NodePluginManifest(
                typeId = CRON_TRIGGER,
                displayName = "Cron Schedule Trigger",
                description = "Triggers workflow automatically on a scheduled interval or cron expression",
                category = NodeCategory.TRIGGER,
                iconResName = "ic_node_cron_trigger",
                outputVariables = listOf("body", "text", "sender", "timestamp"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "cronExpression",
                            label = "Schedule Interval / Cron Expression",
                            placeholder = "every 30m, hourly, or 0 8 * * *",
                            tooltip = "Frequency of scheduled execution (e.g. 'every 15m', 'hourly', 'daily', or cron format)",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "testPayload",
                            label = "Simulated Cron Payload",
                            placeholder = "Scheduled execution triggered",
                            singleLine = true,
                            isTestField = true,
                        ),
                    ),
            ),
            // 3. Parse Notification
            NodePluginManifest(
                typeId = PARSE_NOTIFICATION,
                displayName = "Parse Notification",
                description = "Extracts text fields from incoming app notifications",
                category = NodeCategory.EXTRACTION,
                iconResName = "ic_node_parse_notification",
                outputVariables = listOf("code", "otp"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "regex",
                            label = "Regular Expression Pattern",
                            placeholder = """\b\d{6}\b""",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "outputKey",
                            label = "Output Variable Name",
                            placeholder = "code",
                            singleLine = true,
                        ),
                    ),
            ),
            // 4. Regex Parser
            NodePluginManifest(
                typeId = REGEX_PARSER,
                displayName = "Regex Parser",
                description = "Extracts fields using regular expressions",
                category = NodeCategory.EXTRACTION,
                iconResName = "ic_node_regex_parser",
                outputVariables = listOf("code", "otp"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "regex",
                            label = "Regular Expression Pattern",
                            placeholder = """\b\d{6}\b""",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "outputKey",
                            label = "Output Variable Name",
                            placeholder = "code",
                            singleLine = true,
                        ),
                    ),
            ),
            // 5. JSON Parser
            NodePluginManifest(
                typeId = JSON_PARSER,
                displayName = "JSON Parser",
                description = "Parses JSON payloads supporting nested paths, array indexing, wildcards, recursive search, and type conversions",
                category = NodeCategory.EXTRACTION,
                iconResName = "ic_node_json_parser",
                outputVariables = listOf("json_output", "code", "otp"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "jsonKey",
                            label = "JSON Field Path / Query",
                            placeholder = "data.status, items[0].name, ..otp",
                            tooltip = "JSON property path. Supports nested keys, array indexing [0], negative index [-1], wildcards [*], or recursive search ..key",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "outputKey",
                            label = "Output Variable Name",
                            placeholder = "parsedStatus",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "inputVariable",
                            label = "Input Variable (Optional)",
                            placeholder = "http_response",
                            tooltip = "Variable containing JSON to parse. Defaults to http_response or body.",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "targetType",
                            label = "Target Type (AUTO, STRING, NUMBER, BOOLEAN, JSON_STRING, COUNT, ARRAY)",
                            placeholder = "AUTO",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "defaultValue",
                            label = "Default Fallback Value",
                            placeholder = "N/A",
                            singleLine = true,
                        ),
                    ),
            ),
            // 6. Template Formatter
            NodePluginManifest(
                typeId = TEMPLATE_FORMATTER,
                displayName = "Template Formatter",
                description = "Formats custom message templates using variables",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_template_formatter",
                outputVariables = listOf("formatted_payload", "payload"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.CodeTemplateField(
                            key = "template",
                            label = "Message Template",
                            tooltip = "Use \${variable} placeholders",
                            minLines = 3,
                        ),
                    ),
            ),
            // 7. Condition Evaluator
            NodePluginManifest(
                typeId = CONDITION,
                displayName = "Condition Evaluator",
                description = "Evaluates conditional logic before proceeding",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_condition",
                outputVariables = emptyList(),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "variable",
                            label = "Variable Name",
                            placeholder = "code",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "operator",
                            label = "Comparison Operator",
                            options =
                                listOf(
                                    "EQUALS" to "Equals",
                                    "NOT_EQUALS" to "Does Not Equal",
                                    "CONTAINS" to "Contains",
                                    "REGEX_MATCH" to "Regex Match",
                                ),
                        ),
                        ConfigFieldSpec.TextField(
                            key = "value",
                            label = "Target Value",
                            singleLine = true,
                        ),
                    ),
            ),
            // 8. HTTP Webhook Action
            NodePluginManifest(
                typeId = HTTP_ACTION,
                displayName = "HTTP Webhook Action",
                description = "Sends an HTTP POST/GET request to a target server",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_http_action",
                outputVariables = listOf("http_response", "http_status"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "url",
                            label = "Target Webhook URL",
                            placeholder = "https://api.example.com/webhook",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "method",
                            label = "HTTP Method",
                            options =
                                listOf(
                                    "POST" to "POST",
                                    "GET" to "GET",
                                    "PUT" to "PUT",
                                ),
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "queryParams",
                            label = "Query Parameters (key=value, one per line)",
                            minLines = 2,
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "headers",
                            label = "HTTP Headers",
                            minLines = 2,
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "bodyTemplate",
                            label = "Body Template / Payload",
                            minLines = 3,
                        ),
                    ),
            ),
            // 9. Send Notification Action
            NodePluginManifest(
                typeId = SEND_NOTIFICATION_ACTION,
                displayName = "Send System Notification",
                description = "Displays a system notification on device",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_send_notification",
                outputVariables = emptyList(),
                configSchema =
                    listOf(
                        ConfigFieldSpec.CodeTemplateField(
                            key = "title",
                            label = "Notification Title",
                            tooltip = "Use \${variable} placeholders",
                            minLines = 1,
                            singleLine = true,
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "body",
                            label = "Notification Content",
                            minLines = 2,
                            singleLine = false,
                        ),
                    ),
            ),
            // 10. VPN Condition Check
            NodePluginManifest(
                typeId = VPN_CHECK,
                displayName = "VPN Condition Check",
                description = "Inspects VPN tunnel state (WireGuard, Tailscale, OpenVPN, tun), exposes connection variables, and verifies connection requirements",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_vpn",
                outputVariables = listOf("vpn_connected", "vpn_interface", "vpn_type"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.DropdownField(
                            key = "requireConnected",
                            label = "VPN Connection Requirement",
                            options =
                                listOf(
                                    "true" to "Require VPN Connected",
                                    "false" to "Don't Require (Inspect & Expose Variables)",
                                ),
                        ),
                        ConfigFieldSpec.TextField(
                            key = "requiredInterface",
                            label = "Required Interface Filter (Optional)",
                            placeholder = "tun, wg, or tailscale",
                            singleLine = true,
                        ),
                    ),
            ),
            // 11. GPS / Location Condition Check
            NodePluginManifest(
                typeId = GPS_CHECK,
                displayName = "GPS / Location Condition Check",
                description = "Checks if device Location / GPS hardware is enabled, captures coordinates, exposes location variables, and validates state",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_gps",
                requiredPermissions =
                    listOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                outputVariables = listOf("gps_enabled", "latitude", "longitude", "accuracy", "maps_url", "location_provider"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.DropdownField(
                            key = "requireGpsEnabled",
                            label = "GPS Hardware Requirement",
                            options =
                                listOf(
                                    "true" to "Require Location / GPS Enabled",
                                    "false" to "Don't Require (Inspect & Expose Variables)",
                                ),
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "fetchCoordinates",
                            label = "Fetch Coordinates",
                            options =
                                listOf(
                                    "true" to "Fetch Current Coordinates (Latitude, Longitude)",
                                    "false" to "Status Only",
                                ),
                        ),
                    ),
            ),
            // 12. Internet & Network Condition Check
            NodePluginManifest(
                typeId = INTERNET_CHECK,
                displayName = "Internet & Network Condition Check",
                description = "Inspects active Internet connectivity and network transport (Wi-Fi, Cellular, Ethernet), exposes variables, and validates requirements",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_internet",
                requiredPermissions =
                    listOf(
                        android.Manifest.permission.ACCESS_NETWORK_STATE,
                    ),
                outputVariables = listOf("internet_connected", "network_type", "is_metered"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.DropdownField(
                            key = "requireInternet",
                            label = "Internet Connection Requirement",
                            options =
                                listOf(
                                    "true" to "Require Active Connection",
                                    "false" to "Don't Require (Inspect Only)",
                                ),
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "requiredNetworkType",
                            label = "Required Transport Type",
                            options =
                                listOf(
                                    "ANY" to "Any Internet Connection",
                                    "WIFI" to "Wi-Fi Only",
                                    "CELLULAR" to "Cellular Only",
                                ),
                        ),
                    ),
            ),
            // 13. Bluetooth Condition Check
            NodePluginManifest(
                typeId = BLUETOOTH_CHECK,
                displayName = "Bluetooth Condition Check",
                description = "Inspects Bluetooth power status (on/off), connected accessories/devices, exposes device variables, and validates state",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_bluetooth",
                requiredPermissions =
                    listOf(
                        "android.permission.BLUETOOTH",
                        "android.permission.BLUETOOTH_CONNECT",
                    ),
                outputVariables =
                    listOf(
                        "bluetooth_enabled",
                        "bluetooth_connected",
                        "connected_device_name",
                        "connected_device_address",
                        "bonded_device_count",
                    ),
                configSchema =
                    listOf(
                        ConfigFieldSpec.DropdownField(
                            key = "requireBluetoothEnabled",
                            label = "Bluetooth Power Requirement",
                            options =
                                listOf(
                                    "true" to "Require Bluetooth On",
                                    "false" to "Don't Require (Inspect Only)",
                                ),
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "requireConnected",
                            label = "Connected Device Requirement",
                            options =
                                listOf(
                                    "false" to "Don't Require",
                                    "true" to "Require Connected Device",
                                ),
                        ),
                        ConfigFieldSpec.TextField(
                            key = "targetDeviceName",
                            label = "Target Device Name Filter (Optional)",
                            placeholder = "Car Audio, AirPods, Headset",
                            singleLine = true,
                        ),
                    ),
            ),
            // 14. SIM Card Condition Check
            NodePluginManifest(
                typeId = SIM_CHECK,
                displayName = "SIM Card Condition Check",
                description = "Inspects SIM card ready status, carrier name, active subscriptions, exposes carrier variables, and validates requirements",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_sim",
                requiredPermissions =
                    listOf(
                        android.Manifest.permission.READ_PHONE_STATE,
                    ),
                outputVariables = listOf("sim_ready", "carrier_name", "sim_count", "is_roaming"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.DropdownField(
                            key = "requireSimReady",
                            label = "SIM Ready Requirement",
                            options =
                                listOf(
                                    "true" to "Require Ready SIM Card",
                                    "false" to "Don't Require (Inspect Only)",
                                ),
                        ),
                        ConfigFieldSpec.TextField(
                            key = "requiredCarrier",
                            label = "Required Carrier Filter (Optional)",
                            placeholder = "Jio, Airtel, T-Mobile",
                            singleLine = true,
                        ),
                    ),
            ),
            // 15. Battery & Power Condition Check
            NodePluginManifest(
                typeId = BATTERY_CHECK,
                displayName = "Battery & Power Condition Check",
                description = "Inspects battery level percentage, charging status, exposes power variables, and validates battery requirements",
                category = NodeCategory.LOGIC,
                iconResName = "ic_node_battery",
                outputVariables = listOf("battery_percent", "is_charging", "charging_status"),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "minBatteryPercent",
                            label = "Minimum Battery Level (%)",
                            placeholder = "15",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "requireCharging",
                            label = "Require Device Charging",
                            options =
                                listOf(
                                    "false" to "Don't Require Charging",
                                    "true" to "Require Charging",
                                ),
                        ),
                    ),
            ),
        )

    override fun getCredentialSpecs(): List<CredentialSpec> =
        listOf(
            CredentialSpec(
                typeId = CredentialType.BEARER_TOKEN,
                displayName = "Bearer Token (API Key)",
                description = "Encrypted API Bearer Token key",
                fields =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "token",
                            label = "Token / API Key",
                            singleLine = true,
                        ),
                    ),
            ),
            CredentialSpec(
                typeId = CredentialType.BASIC_AUTH,
                displayName = "Basic Auth (Username & Password)",
                description = "HTTP Basic Authentication credentials",
                fields =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "username",
                            label = "Username",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "password",
                            label = "Password",
                            singleLine = true,
                        ),
                    ),
            ),
            CredentialSpec(
                typeId = CredentialType.HEADER_SET,
                displayName = "Custom Headers",
                description = "Custom HTTP headers string",
                fields =
                    listOf(
                        ConfigFieldSpec.CodeTemplateField(
                            key = "headers",
                            label = "Custom Headers (Key: Value)",
                            minLines = 3,
                        ),
                    ),
            ),
        )

    override fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        val config =
            try {
                JSONObject(configJson)
            } catch (_: JSONException) {
                JSONObject()
            }
        return when {
            typeId.contains("SEND_SMS_ACTION", ignoreCase = true) -> validateSmsAction(config)
            typeId.contains("SEND_NOTIFICATION_ACTION", ignoreCase = true) -> validateNotificationAction(config)
            typeId.contains("HTTP_ACTION", ignoreCase = true) -> validateHttpAction(config)
            typeId.contains("REGEX_PARSER", ignoreCase = true) ||
                typeId.contains("PARSE_NOTIFICATION", ignoreCase = true) -> validateRegexParser(config)
            typeId.contains("JSON_PARSER", ignoreCase = true) -> validateJsonParser(config)
            typeId.contains("TEMPLATE_FORMATTER", ignoreCase = true) -> validateTemplateFormatter(config)
            typeId.contains("CONDITION", ignoreCase = true) -> validateCondition(config)
            typeId.contains("CRON_TRIGGER", ignoreCase = true) -> validateCronTrigger(config)
            else -> null
        }
    }

    private fun validateSmsAction(config: JSONObject): String? {
        val recipient = config.optString("recipient", "").trim()
        val body = config.optString("body", "").trim().ifEmpty { "\${body}" }
        return when {
            recipient.isBlank() -> "Recipient phone number cannot be blank."
            body.isBlank() -> "Message body cannot be blank."
            else -> null
        }
    }

    private fun validateNotificationAction(config: JSONObject): String? {
        val title = config.optString("title", "").trim().ifEmpty { "ZopHop Notification" }
        val body = config.optString("body", "").trim().ifEmpty { "\${body}" }
        return when {
            title.isBlank() -> "Notification title cannot be blank."
            body.isBlank() -> "Notification content body cannot be blank."
            else -> null
        }
    }

    private fun validateHttpAction(config: JSONObject): String? {
        val credentialId = config.optString("credentialId", "").trim()
        val url = config.optString("url", "").ifBlank { config.optString("serverUrl", "") }.trim()
        return when {
            url.isBlank() && credentialId.isBlank() -> "HTTP URL cannot be blank."
            url.isNotBlank() &&
                !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true) -> "HTTP URL must start with http:// or https://."
            else -> null
        }
    }

    private fun validateRegexParser(config: JSONObject): String? {
        val regex = config.optString("regex", "").trim().ifEmpty { "\\b\\d{6}\\b" }
        val outputKey = config.optString("outputKey", "").trim().ifEmpty { "code" }
        if (regex.isBlank()) return "Regular expression pattern cannot be blank."
        return try {
            Pattern.compile(regex)
            if (outputKey.isBlank()) "Output variable name cannot be blank." else null
        } catch (e: PatternSyntaxException) {
            "Invalid regular expression: ${e.message}"
        }
    }

    private fun validateJsonParser(config: JSONObject): String? {
        val jsonKey = config.optString("jsonKey", "").trim().ifEmpty { "data.status" }
        val outputKey = config.optString("outputKey", "").trim().ifEmpty { "parsedStatus" }
        return when {
            jsonKey.isBlank() -> "JSON path key cannot be blank."
            outputKey.isBlank() -> "Output variable name cannot be blank."
            else -> null
        }
    }

    private fun validateTemplateFormatter(config: JSONObject): String? {
        val template = config.optString("template", "").trim().ifEmpty { "\${body}" }
        return if (template.isBlank()) "Template text cannot be blank." else null
    }

    private fun validateCondition(config: JSONObject): String? {
        val variable = config.optString("variable", "").trim().ifEmpty { "status" }
        val operator = config.optString("operator", "").trim().ifEmpty { "EQUALS" }
        return when {
            variable.isBlank() -> "Condition variable cannot be blank."
            operator.isBlank() -> "Condition operator must be selected."
            else -> null
        }
    }

    private fun validateCronTrigger(config: JSONObject): String? {
        val cronExpression = config.optString("cronExpression", "").trim().ifEmpty { "every 30m" }
        return if (cronExpression.isBlank()) "Schedule interval or cron expression cannot be blank." else null
    }
}
