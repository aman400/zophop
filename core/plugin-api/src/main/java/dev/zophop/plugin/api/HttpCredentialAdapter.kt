package dev.zophop.plugin.api

import okhttp3.Request

/**
 * Bridge interface allowing plugins and core modules to adapt credentials
 * for HTTP requests. Decouples HTTP execution and UI editors from specific
 * plugin authentication or URL layout schemes.
 */
interface HttpCredentialAdapter {
    /**
     * Unique credential type handled by this adapter (e.g. "NTFY_AUTH", "GOTIFY_AUTH", "BEARER_TOKEN").
     */
    val supportedCredentialType: String

    /**
     * Name of the sensitive header key used by this credential (e.g. "Authorization", "X-Gotify-Key"),
     * or null if no single secret header is used.
     */
    fun getSecretHeaderName(dataMap: Map<String, String>): String? = null

    /**
     * Maps credential data into an HTTP node configuration map upon credential attachment in the UI.
     *
     * @param dataMap The credential's key-value data.
     * @param currentConfig The current configuration map of the HTTP node.
     * @return Map of updated configuration keys and values to merge into the HTTP node config.
     */
    fun configureHttpNode(
        dataMap: Map<String, String>,
        currentConfig: Map<String, String>,
    ): Map<String, String>

    /**
     * Fills missing or default-valued fields in an HTTP node configuration map from the credential.
     * Fields already customized by the user are not overwritten.
     *
     * @param dataMap The credential's key-value data.
     * @param currentConfig The current configuration map of the HTTP node.
     * @return Map of configuration keys and values to fill for missing fields.
     */
    fun fillMissingHttpNodeConfig(
        dataMap: Map<String, String>,
        currentConfig: Map<String, String>,
    ): Map<String, String>

    /**
     * Injects authentication, headers, or query parameters into OkHttp [Request.Builder] at runtime.
     *
     * @param builder The OkHttp request builder.
     * @param dataMap The credential's key-value data.
     * @param context Execution context for variable interpolation.
     */
    fun applyToRequest(
        builder: Request.Builder,
        dataMap: Map<String, String>,
        context: ExecutionContext,
    )
}

object HttpHeaderConfigHelper {
    /**
     * Ensures that [headerName] is present in the newline/comma-separated [headersText].
     * If not present, appends `[headerName]: ` (with blank value) so the UI can prompt for overrides
     * without exposing secrets in plain text.
     */
    fun ensureHeaderPresent(
        headersText: String?,
        headerName: String,
    ): String {
        val raw = headersText.orEmpty()
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val headerKeyPattern = headerName.trim().lowercase()
        val alreadyPresent =
            lines.any { line ->
                val key = line.substringBefore(':').substringBefore('=').trim().lowercase()
                key == headerKeyPattern
            }
        return if (!alreadyPresent) {
            val prefix = if (raw.isBlank()) "" else raw.trimEnd() + "\n"
            "$prefix$headerName: "
        } else {
            raw
        }
    }
}
