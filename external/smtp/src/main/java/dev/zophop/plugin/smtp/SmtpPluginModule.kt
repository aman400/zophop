package dev.zophop.plugin.smtp

import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.CredentialSpec
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodeExecutor
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.plugin.api.NodePluginModule
import okhttp3.OkHttpClient

object SmtpPluginModule : NodePluginModule {
    const val TYPE_ID_SMTP_ACTION = "dev.zophop.plugin.smtp.SMTP_ACTION"
    const val CREDENTIAL_TYPE_SMTP_AUTH = "SMTP_AUTH"
    override val moduleId: String = "smtp-plugin-module"

    override fun createExecutor(
        typeId: String,
        okHttpClient: OkHttpClient,
        credentialRepository: Any?,
    ): NodeExecutor? =
        when {
            typeId.contains("SMTP_ACTION") -> SmtpActionExecutor(credentialRepository = credentialRepository)
            else -> null
        }

    override fun validateConfig(
        typeId: String,
        configJson: String,
    ): String? {
        if (!typeId.contains("SMTP", ignoreCase = true)) return null
        val config = parseJsonObjectSafe(configJson)
        val host = config.optString("host", "").trim()
        val portRaw = config.optString("port", "").trim()
        val toAddresses = config.optString("toAddresses", "").trim()
        val credentialId = config.optString("credentialId", "").trim()
        val portValid = portRaw.isBlank() || (portRaw.toIntOrNull() in 1..65_535)

        return when {
            host.isBlank() && credentialId.isBlank() ->
                "Either SMTP Host or an attached SMTP credential must be provided."
            !portValid ->
                "SMTP Port must be a number between 1 and 65535."
            toAddresses.isBlank() ->
                "At least one recipient (To Email Addresses) must be provided."
            else ->
                null
        }
    }

    override fun getCredentialSpecs(): List<CredentialSpec> = getManifests().mapNotNull { it.credentialSpec }

    override fun getManifests(): List<NodePluginManifest> =
        listOf(
            NodePluginManifest(
                typeId = TYPE_ID_SMTP_ACTION,
                displayName = "Send Email (SMTP)",
                description = "Sends plain-text or HTML emails via an SMTP server (STARTTLS, SMTPS, or Plain)",
                category = NodeCategory.ACTION,
                iconResName = "ic_node_smtp",
                requiredCredentialType = CREDENTIAL_TYPE_SMTP_AUTH,
                defaultConfigJson =
                    """{"port":"587","security":"STARTTLS","contentType":"text/plain","subject":"ZopHop Alert","bodyTemplate":"${'$'}{body}"}""",
                credentialSpec =
                    CredentialSpec(
                        typeId = CREDENTIAL_TYPE_SMTP_AUTH,
                        displayName = "SMTP Email Account",
                        description = "SMTP server host, port, username, and password/app password",
                        fields =
                            listOf(
                                ConfigFieldSpec.TextField(
                                    key = "host",
                                    label = "SMTP Host",
                                    placeholder = "smtp.gmail.com",
                                    singleLine = true,
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "port",
                                    label = "SMTP Port",
                                    placeholder = "587",
                                    singleLine = true,
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "username",
                                    label = "Username / Email",
                                    placeholder = "user@example.com",
                                    singleLine = true,
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "password",
                                    label = "Password / App Password",
                                    singleLine = true,
                                    isSecret = true,
                                ),
                                ConfigFieldSpec.TextField(
                                    key = "fromAddress",
                                    label = "Default From Address (Optional)",
                                    placeholder = "user@example.com",
                                    singleLine = true,
                                ),
                            ),
                    ),
                configSchema =
                    listOf(
                        ConfigFieldSpec.TextField(
                            key = "host",
                            label = "SMTP Host (Leave blank if credential attached)",
                            tooltip = "SMTP server hostname (e.g. smtp.gmail.com)",
                            placeholder = "smtp.gmail.com",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "port",
                            label = "SMTP Port",
                            tooltip = "587 for STARTTLS, 465 for SSL/TLS (SMTPS), 25 for Plain",
                            placeholder = "587",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "security",
                            label = "Encryption / Security",
                            tooltip = "TLS transport security mode",
                            options =
                                listOf(
                                    "STARTTLS" to "STARTTLS (Port 587)",
                                    "SMTPS" to "SSL / TLS (Port 465)",
                                    "PLAIN" to "None / Plain (Port 25)",
                                ),
                        ),
                        ConfigFieldSpec.TextField(
                            key = "username",
                            label = "SMTP Username (Leave blank if credential attached)",
                            tooltip = "SMTP login username or email address",
                            placeholder = "user@example.com",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "password",
                            label = "SMTP Password (Leave blank if credential attached)",
                            tooltip = "SMTP password or app password (encrypted if stored in a credential)",
                            singleLine = true,
                            isSecret = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "fromAddress",
                            label = "From Email Address (Optional)",
                            tooltip = "Sender address (defaults to SMTP username if omitted)",
                            placeholder = "alerts@example.com",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "toAddresses",
                            label = "To Email Addresses (Comma separated)",
                            tooltip = "One or more recipient email addresses separated by commas",
                            placeholder = "recipient@example.com",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "ccAddresses",
                            label = "CC Email Addresses (Optional)",
                            tooltip = "Optional CC recipient email addresses separated by commas",
                            placeholder = "cc@example.com",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.TextField(
                            key = "subject",
                            label = "Subject",
                            tooltip = "Email subject template (supports \${sender}, \${body}, etc.)",
                            placeholder = "ZopHop Alert",
                            singleLine = true,
                        ),
                        ConfigFieldSpec.DropdownField(
                            key = "contentType",
                            label = "Body Format",
                            tooltip = "Choose plain text or HTML MIME content type",
                            options =
                                listOf(
                                    "text/plain" to "Plain Text",
                                    "text/html" to "HTML",
                                ),
                        ),
                        ConfigFieldSpec.CodeTemplateField(
                            key = "bodyTemplate",
                            label = "Email Body Template",
                            tooltip = "Email body template (supports \${body}, \${sender}, etc.)",
                            minLines = 4,
                        ),
                    ),
            ),
        )
}
