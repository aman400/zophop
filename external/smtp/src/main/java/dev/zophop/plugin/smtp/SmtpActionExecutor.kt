package dev.zophop.plugin.smtp

import android.content.Context
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.ExecutionLogFormatter
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.api.NodeExecutor
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

class SmtpActionExecutor(
    private val smtpTransport: SmtpTransport = SmtpClient(),
    private val credentialRepository: Any? = null,
) : NodeExecutor {
    override suspend fun execute(
        node: WorkflowNode,
        context: ExecutionContext,
        appContext: Context?,
    ): NodeExecutionResult {
        val config = parseJsonObjectSafe(node.configJson)
        val resolved = resolveEffectiveConfig(config, context, credentialRepository)
        val explicitSecrets = collectSecretsToRedact(resolved.password, resolved.username)

        val validationError = validateResolvedConfig(resolved)
        if (validationError != null) {
            return NodeExecutionResult.Failed(
                ExecutionLogFormatter.redactSecrets(validationError, explicitSecrets),
            )
        }

        return try {
            val request =
                SmtpMessageRequest(
                    host = resolved.host,
                    port = resolved.port,
                    security = resolved.security,
                    username = resolved.username,
                    password = resolved.password,
                    fromAddress = resolved.fromAddress,
                    toAddresses = resolved.toAddresses,
                    ccAddresses = resolved.ccAddresses,
                    subject = resolved.subject,
                    body = resolved.body,
                    contentType = resolved.contentType,
                )
            smtpTransport.sendEmail(request)
            val recipientsSummary = (resolved.toAddresses + resolved.ccAddresses).joinToString(", ")
            NodeExecutionResult.Success(
                logMessage = "Email sent via ${resolved.host}:${resolved.port} (${resolved.security.name}) to $recipientsSummary",
            )
        } catch (e: SmtpException) {
            val safeReason = ExecutionLogFormatter.redactSecrets(e.message ?: "SMTP error", explicitSecrets)
            NodeExecutionResult.Failed("SMTP delivery failed: $safeReason")
        } catch (e: IOException) {
            val safeReason = ExecutionLogFormatter.redactSecrets(e.message ?: "Network IO error", explicitSecrets)
            NodeExecutionResult.Failed("SMTP connection error: $safeReason")
        } catch (e: IllegalArgumentException) {
            val safeReason = ExecutionLogFormatter.redactSecrets(e.message ?: "Invalid configuration", explicitSecrets)
            NodeExecutionResult.Failed("SMTP invalid configuration: $safeReason")
        }
    }

    private fun validateResolvedConfig(resolved: ResolvedSmtpConfig): String? =
        when {
            resolved.host.isBlank() -> "SMTP host is empty"
            resolved.port !in 1..65_535 -> "SMTP port must be between 1 and 65535"
            resolved.fromAddress.isBlank() -> "From email address is empty"
            resolved.toAddresses.isEmpty() -> "To email address is empty"
            resolved.username.isNotBlank() && resolved.password.isBlank() ->
                "SMTP password is empty for configured username"
            resolved.password.isNotBlank() && resolved.username.isBlank() ->
                "SMTP username is empty for configured password"
            else -> null
        }

    private fun collectSecretsToRedact(
        password: String,
        username: String,
    ): List<String> {
        if (password.isBlank()) return emptyList()
        val encodedPassword = Base64.getEncoder().encodeToString(password.toByteArray(StandardCharsets.UTF_8))
        val encodedPlainAuth =
            Base64.getEncoder().encodeToString(
                "\u0000$username\u0000$password".toByteArray(StandardCharsets.UTF_8),
            )
        return listOf(password, encodedPassword, encodedPlainAuth).filter { it.isNotBlank() }
    }

    internal data class ResolvedSmtpConfig(
        val host: String,
        val port: Int,
        val security: SmtpSecurityMode,
        val username: String,
        val password: String,
        val fromAddress: String,
        val toAddresses: List<String>,
        val ccAddresses: List<String>,
        val subject: String,
        val body: String,
        val contentType: String,
    )

    companion object {
        private const val DEFAULT_PORT_STR = "587"

        internal fun resolveEffectiveConfig(
            config: JsonObject,
            context: ExecutionContext,
            credentialRepository: Any?,
        ): ResolvedSmtpConfig {
            val credentialId = config.optString("credentialId", "").trim()
            val repoCredMap = loadCredentialDataMap(credentialRepository, credentialId)

            val configHost = config.optString("host", "").trim()
            val rawHost = configHost.ifBlank { resolveField(context, repoCredMap, "host") }
            val host = context.interpolate(rawHost).trim()

            val rawSecurity =
                config.optString("security", "").trim().ifBlank {
                    resolveField(context, repoCredMap, "security").ifBlank { "STARTTLS" }
                }
            val security = SmtpSecurityMode.fromString(rawSecurity)

            val configPort = config.optString("port", "").trim()
            val credPort = resolveField(context, repoCredMap, "port")
            val isDefaultOrBlankPort = configPort.isBlank() || configPort == DEFAULT_PORT_STR
            val portStr =
                if (configHost.isBlank() && credPort.isNotBlank() && isDefaultOrBlankPort) {
                    credPort
                } else {
                    configPort.ifBlank { credPort }
                }
            val port = portStr.toIntOrNull() ?: security.defaultPort

            val username =
                config.optString("username", "").trim().ifBlank {
                    resolveField(context, repoCredMap, "username")
                }
            val password =
                config.optString("password", "").trim().ifBlank {
                    resolveField(context, repoCredMap, "password", "token", "value")
                }

            val rawFrom =
                config.optString("fromAddress", "").trim().ifBlank {
                    resolveField(context, repoCredMap, "fromAddress", "from").ifBlank { username }
                }
            val fromAddress = context.interpolate(rawFrom).trim()

            val toRaw = context.interpolate(config.optString("toAddresses", ""))
            val ccRaw = context.interpolate(config.optString("ccAddresses", ""))
            val subjectTemplate = config.optString("subject", "").ifBlank { "ZopHop Alert" }
            val subject = context.interpolate(subjectTemplate).trim()

            val bodyTemplate = config.optString("bodyTemplate", "\${body}").ifEmpty { "\${body}" }
            val body = context.interpolate(bodyTemplate)
            val contentType =
                config.optString("contentType", SmtpMessageRequest.CONTENT_TYPE_PLAIN)
                    .trim()
                    .ifBlank { SmtpMessageRequest.CONTENT_TYPE_PLAIN }

            return ResolvedSmtpConfig(
                host = host,
                port = port,
                security = security,
                username = username,
                password = password,
                fromAddress = fromAddress,
                toAddresses = SmtpClient.parseAddressList(toRaw),
                ccAddresses = SmtpClient.parseAddressList(ccRaw),
                subject = subject,
                body = body,
                contentType = contentType,
            )
        }

        private fun resolveField(
            context: ExecutionContext,
            repoCredMap: Map<String, String>,
            vararg keys: String,
        ): String =
            keys.firstNotNullOfOrNull { key ->
                context.getString("_cred_$key", "").trim().takeIf { it.isNotBlank() }
            } ?: keys.firstNotNullOfOrNull { key ->
                repoCredMap[key]?.trim()?.takeIf { it.isNotBlank() }
            }.orEmpty()

        private fun loadCredentialDataMap(
            credentialRepository: Any?,
            credentialId: String,
        ): Map<String, String> {
            if (credentialRepository == null || credentialId.isBlank()) return emptyMap()
            return try {
                val method = credentialRepository.javaClass.methods.firstOrNull { it.name == "getCredentialById" }
                if (method != null && method.parameterCount == 1) {
                    val cred = method.invoke(credentialRepository, credentialId) ?: return emptyMap()
                    extractDataMap(cred).ifEmpty { extractConfigJsonMap(cred) }
                } else {
                    emptyMap()
                }
            } catch (_: ReflectiveOperationException) {
                emptyMap()
            }
        }

        private fun extractDataMap(cred: Any): Map<String, String> =
            try {
                val mapMethod = cred.javaClass.getMethod("getDataMap")
                val rawMap = mapMethod.invoke(cred) as? Map<*, *> ?: return emptyMap()
                buildMap {
                    for ((k, v) in rawMap) {
                        val keyStr = k?.toString()?.trim().orEmpty()
                        val valStr = v?.toString()?.trim().orEmpty()
                        if (keyStr.isNotEmpty() && valStr.isNotEmpty()) {
                            put(keyStr, valStr)
                        }
                    }
                }
            } catch (_: ReflectiveOperationException) {
                emptyMap()
            }

        private fun extractConfigJsonMap(cred: Any): Map<String, String> =
            try {
                val jsonMethod = cred.javaClass.getMethod("getConfigJson")
                val configJson = jsonMethod.invoke(cred) as? String ?: return emptyMap()
                if (configJson.isBlank()) return emptyMap()
                val jsonObj = parseJsonObjectSafe(configJson)
                buildMap {
                    for (key in jsonObj.keys) {
                        val value = jsonObj.optString(key, "").trim()
                        if (value.isNotEmpty()) {
                            put(key, value)
                        }
                    }
                }
            } catch (_: ReflectiveOperationException) {
                emptyMap()
            }
    }
}
