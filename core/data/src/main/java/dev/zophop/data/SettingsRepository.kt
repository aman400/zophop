package dev.zophop.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object ServerType {
    const val NTFY = "NTFY"
    const val GOTIFY = "GOTIFY"
}

data class AppSettings(
    val nodes: List<DeliveryNode> = emptyList(),
    val allowlist: String = ".*",
    val keywordRegex: String = ".*",
    val senderRegex: String = ".*",
    val bodyParseRegex: String = "\\b\\d{4,8}\\b",
    val customMessageTemplate: String = "\${body}",
    val expiryMinutes: Int = 10,
    val maxRetries: Int = 5,
    val isEnabled: Boolean = false,
    val allowInsecureHttp: Boolean = false,
    val forwardNotifications: Boolean = false,
    val showWorkflowNotifications: Boolean = false,
    val maxParallelExecutions: Int = 3,
    val maxExecutionQueueSize: Int = 20,
    val autoClearEnabled: Boolean = false,
    val autoClearMinutes: Int = 1440,
    val lockCredentialsWithBiometric: Boolean = false,
    val globalWorkflowsEnabled: Boolean = true,
    val dismissedBiometricBanner: Boolean = false,
    val themeMode: String = "SYSTEM",
    val themePalette: String = "EMERALD_AMBER",
) {
    val allowlistItems: List<String>
        get() = allowlist.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    val activeNodes: List<DeliveryNode>
        get() = nodes.filter { it.isEnabled && it.serverUrl.isNotBlank() }
}

interface SettingsRepository {
    fun getSettings(): AppSettings

    fun saveSettings(settings: AppSettings)

    fun setEnabled(enabled: Boolean)
}

class EncryptedSettingsRepository(
    context: Context,
) : SettingsRepository {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)

    override fun getSettings(): AppSettings {
        val rawNodesStored = prefs.getString(KEY_DELIVERY_NODES, "") ?: ""
        val rawNodes = DatabaseEncryption.decrypt(rawNodesStored)
        val nodes = deserializeNodes(rawNodes).ifEmpty { resolveLegacyMigratedNodes() }

        return AppSettings(
            nodes = nodes,
            allowlist = prefs.getString(KEY_ALLOWLIST, ".*") ?: ".*",
            keywordRegex = prefs.getString(KEY_KEYWORD_REGEX, ".*") ?: ".*",
            senderRegex = prefs.getString(KEY_SENDER_REGEX, ".*") ?: ".*",
            bodyParseRegex = prefs.getString(KEY_BODY_PARSE_REGEX, "\\b\\d{4,8}\\b") ?: "\\b\\d{4,8}\\b",
            customMessageTemplate = prefs.getString(KEY_CUSTOM_MESSAGE_TEMPLATE, "\${body}") ?: "\${body}",
            expiryMinutes = prefs.getInt(KEY_EXPIRY_MINUTES, 10),
            maxRetries = prefs.getInt(KEY_MAX_RETRIES, 5),
            isEnabled = prefs.getBoolean(KEY_IS_ENABLED, false),
            allowInsecureHttp = prefs.getBoolean(KEY_ALLOW_INSECURE_HTTP, false),
            forwardNotifications = prefs.getBoolean(KEY_FORWARD_NOTIFICATIONS, false),
            showWorkflowNotifications = prefs.getBoolean(KEY_SHOW_WORKFLOW_NOTIFICATIONS, false),
            maxParallelExecutions = prefs.getInt(KEY_MAX_PARALLEL_EXECUTIONS, 3),
            maxExecutionQueueSize = prefs.getInt(KEY_MAX_EXECUTION_QUEUE_SIZE, 20),
            autoClearEnabled = prefs.getBoolean(KEY_AUTO_CLEAR_ENABLED, false),
            autoClearMinutes = prefs.getInt(KEY_AUTO_CLEAR_MINUTES, 1440),
            lockCredentialsWithBiometric = prefs.getBoolean(KEY_LOCK_CREDENTIALS_WITH_BIOMETRIC, false),
            globalWorkflowsEnabled = prefs.getBoolean(KEY_GLOBAL_WORKFLOWS_ENABLED, true),
            dismissedBiometricBanner = prefs.getBoolean(KEY_DISMISSED_BIOMETRIC_BANNER, false),
            themeMode = getNonBlankString(KEY_THEME_MODE, "SYSTEM"),
            themePalette = getNonBlankString(KEY_THEME_PALETTE, "EMERALD_AMBER"),
        )
    }

    private fun getNonBlankString(
        key: String,
        default: String,
    ): String = prefs.getString(key, default)?.takeIf { it.isNotBlank() } ?: default

    private fun resolveLegacyMigratedNodes(): List<DeliveryNode> {
        val legacyType = prefs.getString(KEY_SERVER_TYPE, ServerType.NTFY) ?: ServerType.NTFY
        val legacyUrl = prefs.getString(KEY_SERVER_URL, "") ?: ""
        val legacyTopic = prefs.getString(KEY_TOPIC, "") ?: ""
        val legacyToken = DatabaseEncryption.decrypt(prefs.getString(KEY_TOKEN, "") ?: "")

        val ntfyUrl = prefs.getString(KEY_NTFY_SERVER_URL, "") ?: if (legacyType == ServerType.NTFY) legacyUrl else ""
        val gotifyUrl = prefs.getString(KEY_GOTIFY_SERVER_URL, "") ?: if (legacyType == ServerType.GOTIFY) legacyUrl else ""
        val activeUrl = if (legacyType == ServerType.GOTIFY) gotifyUrl.ifBlank { legacyUrl } else ntfyUrl.ifBlank { legacyUrl }

        if (activeUrl.isBlank()) return emptyList()

        return listOf(
            DeliveryNode(
                id = "default-migrated-node",
                name = if (legacyType == ServerType.GOTIFY) "Gotify Target Node" else "ntfy Target Node",
                serverType = legacyType,
                serverUrl = activeUrl,
                topic =
                    if (legacyType == ServerType.GOTIFY) {
                        ""
                    } else {
                        prefs.getString(KEY_NTFY_TOPIC, legacyTopic) ?: legacyTopic
                    },
                token =
                    DatabaseEncryption.decrypt(
                        if (legacyType == ServerType.GOTIFY) {
                            prefs.getString(KEY_GOTIFY_TOKEN, legacyToken) ?: legacyToken
                        } else {
                            prefs.getString(KEY_NTFY_TOKEN, legacyToken) ?: legacyToken
                        },
                    ),
                isEnabled = true,
            ),
        )
    }

    override fun saveSettings(settings: AppSettings) {
        val serializedNodes = serializeNodes(settings.nodes)
        val encryptedNodes =
            if (serializedNodes.isBlank()) "" else DatabaseEncryption.encrypt(serializedNodes)
        prefs.edit {
            putString(KEY_DELIVERY_NODES, encryptedNodes)
            putString(KEY_ALLOWLIST, settings.allowlist)
            putString(KEY_KEYWORD_REGEX, settings.keywordRegex)
            putString(KEY_SENDER_REGEX, settings.senderRegex)
            putString(KEY_BODY_PARSE_REGEX, settings.bodyParseRegex)
            putString(KEY_CUSTOM_MESSAGE_TEMPLATE, settings.customMessageTemplate)
            putInt(KEY_EXPIRY_MINUTES, settings.expiryMinutes)
            putInt(KEY_MAX_RETRIES, settings.maxRetries)
            putBoolean(KEY_IS_ENABLED, settings.isEnabled)
            putBoolean(KEY_ALLOW_INSECURE_HTTP, settings.allowInsecureHttp)
            putBoolean(KEY_FORWARD_NOTIFICATIONS, settings.forwardNotifications)
            putBoolean(KEY_SHOW_WORKFLOW_NOTIFICATIONS, settings.showWorkflowNotifications)
            putInt(KEY_MAX_PARALLEL_EXECUTIONS, settings.maxParallelExecutions)
            putInt(KEY_MAX_EXECUTION_QUEUE_SIZE, settings.maxExecutionQueueSize)
            putBoolean(KEY_AUTO_CLEAR_ENABLED, settings.autoClearEnabled)
            putInt(KEY_AUTO_CLEAR_MINUTES, settings.autoClearMinutes)
            putBoolean(KEY_LOCK_CREDENTIALS_WITH_BIOMETRIC, settings.lockCredentialsWithBiometric)
            putBoolean(KEY_GLOBAL_WORKFLOWS_ENABLED, settings.globalWorkflowsEnabled)
            putBoolean(KEY_DISMISSED_BIOMETRIC_BANNER, settings.dismissedBiometricBanner)
            putString(KEY_THEME_MODE, settings.themeMode)
            putString(KEY_THEME_PALETTE, settings.themePalette)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_IS_ENABLED, enabled) }
    }

    companion object {
        private const val PREFS_FILE_NAME = "zophop_encrypted_prefs"
        private const val KEY_SERVER_TYPE = "server_type"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOPIC = "topic"
        private const val KEY_TOKEN = "token"
        private const val KEY_NTFY_SERVER_URL = "ntfy_server_url"
        private const val KEY_NTFY_TOPIC = "ntfy_topic"
        private const val KEY_NTFY_TOKEN = "ntfy_token"
        private const val KEY_GOTIFY_SERVER_URL = "gotify_server_url"
        private const val KEY_GOTIFY_TOKEN = "gotify_token"
        private const val KEY_DELIVERY_NODES = "delivery_nodes"
        private const val KEY_ALLOWLIST = "allowlist"
        private const val KEY_KEYWORD_REGEX = "keyword_regex"
        private const val KEY_SENDER_REGEX = "sender_regex"
        private const val KEY_BODY_PARSE_REGEX = "body_parse_regex"
        private const val KEY_CUSTOM_MESSAGE_TEMPLATE = "custom_message_template"
        private const val KEY_EXPIRY_MINUTES = "expiry_minutes"
        private const val KEY_MAX_RETRIES = "max_retries"
        private const val KEY_IS_ENABLED = "is_enabled"
        private const val KEY_ALLOW_INSECURE_HTTP = "allow_insecure_http"
        private const val KEY_FORWARD_NOTIFICATIONS = "forward_notifications"
        private const val KEY_SHOW_WORKFLOW_NOTIFICATIONS = "show_workflow_notifications"
        private const val KEY_MAX_PARALLEL_EXECUTIONS = "max_parallel_executions"
        private const val KEY_MAX_EXECUTION_QUEUE_SIZE = "max_execution_queue_size"
        private const val KEY_AUTO_CLEAR_ENABLED = "auto_clear_enabled"
        private const val KEY_AUTO_CLEAR_MINUTES = "auto_clear_minutes"
        private const val KEY_LOCK_CREDENTIALS_WITH_BIOMETRIC = "lock_credentials_with_biometric"
        private const val KEY_GLOBAL_WORKFLOWS_ENABLED = "global_workflows_enabled"
        private const val KEY_DISMISSED_BIOMETRIC_BANNER = "dismissed_biometric_banner"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_THEME_PALETTE = "theme_palette"

        fun serializeNodes(nodes: List<DeliveryNode>): String =
            nodes.joinToString("|||") { node ->
                val name = node.name.replace("|", "_").replace(":", "_")
                val type = node.serverType.replace("|", "_")
                val url = node.serverUrl.replace("|", "_")
                val topic = node.topic.replace("|", "_")
                val token = node.token.replace("|", "_")
                val healthUrl = node.customHealthcheckUrl.replace("|", "_")
                val method = node.healthcheckMethod.replace("|", "_")
                val headers = node.healthcheckHeaders.replace("|", "_").replace("\n", "___")
                "${node.id}:::$name:::$type:::$url:::$topic:::$token:::${node.isEnabled}:::${node.requireInternet}:::${node.requireVpn}:::${node.requireHealthcheck}:::$healthUrl:::$method:::$headers:::${node.healthcheckExpectedStatus}"
            }

        fun deserializeNodes(raw: String): List<DeliveryNode> {
            if (raw.isBlank()) return emptyList()
            return raw.split("|||").mapNotNull { entry ->
                val parts = entry.split(":::")
                if (parts.size >= 7) {
                    DeliveryNode(
                        id = parts[0],
                        name = parts[1],
                        serverType = parts[2],
                        serverUrl = parts[3],
                        topic = parts[4],
                        token = parts[5],
                        isEnabled = parts[6].toBooleanStrictOrNull() ?: true,
                        requireInternet = if (parts.size >= 8) parts[7].toBooleanStrictOrNull() ?: true else true,
                        requireVpn = if (parts.size >= 9) parts[8].toBooleanStrictOrNull() ?: false else false,
                        requireHealthcheck = if (parts.size >= 10) parts[9].toBooleanStrictOrNull() ?: false else false,
                        customHealthcheckUrl = if (parts.size >= 11) parts[10] else "",
                        healthcheckMethod = if (parts.size >= 12 && parts[11].isNotBlank()) parts[11] else "GET",
                        healthcheckHeaders = if (parts.size >= 13) parts[12].replace("___", "\n") else "",
                        healthcheckExpectedStatus = if (parts.size >= 14) parts[13].toIntOrNull() ?: 200 else 200,
                    )
                } else {
                    null
                }
            }
        }
    }
}
