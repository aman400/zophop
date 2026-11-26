package dev.zophop.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.zophop.data.DeliveryNode
import dev.zophop.domain.engine.HttpHeaderUtils
import dev.zophop.plugin.api.ExecutionLogFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.TimeUnit

sealed class PreCheckResult {
    object Passed : PreCheckResult()

    data class Failed(
        val reason: String,
    ) : PreCheckResult()
}

open class PreCheckManager(
    private val context: Context?,
    private val okHttpClient: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build(),
) {
    private val vpnKeywords = listOf("tun", "ppp", "tailscale", "wireguard", "utun")

    fun evaluate(node: DeliveryNode): PreCheckResult {
        if (!node.isEnabled) return PreCheckResult.Passed

        if (node.requireInternet && !isInternetAvailable()) {
            return PreCheckResult.Failed("No active Internet connection available")
        }

        if (node.requireVpn && !isVpnConnected()) {
            return PreCheckResult.Failed("No active VPN connection detected")
        }

        return PreCheckResult.Passed
    }

    open suspend fun verifyNodePreChecks(node: DeliveryNode): PreCheckResult = evaluate(node)

    fun isInternetAvailable(): Boolean {
        val ctx = context ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun isVpnConnected(): Boolean {
        val ctx = context
        if (ctx != null && checkNetworkCapabilitiesVpn(ctx)) return true
        return checkNetworkInterfacesVpn()
    }

    private fun checkNetworkCapabilitiesVpn(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun checkNetworkInterfacesVpn(): Boolean = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        interfaces.any { iface ->
            iface.isUp && vpnKeywords.any { iface.name.lowercase().contains(it) }
        }
    } catch (_: SocketException) {
        false
    }

    suspend fun checkCustomServerHealth(
        serverUrl: String,
        customUrl: String = "",
        method: String = "GET",
        headers: String = "",
        expectedStatus: Int = 200,
    ): PreCheckResult =
        withContext(Dispatchers.IO) {
            val isCustomEndpoint = customUrl.isNotBlank()
            val target =
                if (isCustomEndpoint) {
                    customUrl.trim()
                } else {
                    "${serverUrl.trimEnd('/')}/v1/health"
                }

            val parsedUrl = target.toHttpUrlOrNull()
            when {
                target.isBlank() || parsedUrl == null || (parsedUrl.scheme != "http" && parsedUrl.scheme != "https") -> {
                    val safeTarget = ExecutionLogFormatter.redactUrl(target)
                    PreCheckResult.Failed("Invalid target URL format: $safeTarget")
                }
                parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty() -> {
                    PreCheckResult.Failed("Invalid target URL: embedded credentials are not allowed")
                }
                else -> executeCustomHealthRequest(parsedUrl, method, headers, expectedStatus, isCustomEndpoint)
            }
        }

    private fun executeCustomHealthRequest(
        parsedUrl: okhttp3.HttpUrl,
        method: String,
        headers: String,
        expectedStatus: Int,
        isCustomEndpoint: Boolean,
    ): PreCheckResult {
        val explicitSecrets = collectSecrets(parsedUrl, headers)
        return try {
            val request = buildHealthcheckRequest(parsedUrl, method, headers)
            clientCallCustom(request, expectedStatus, isCustomEndpoint, explicitSecrets)
        } catch (e: IOException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            PreCheckResult.Failed("Healthcheck request error: $safeMsg")
        } catch (e: IllegalArgumentException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            PreCheckResult.Failed("Healthcheck request error: $safeMsg")
        }
    }

    private fun buildHealthcheckRequest(
        parsedUrl: okhttp3.HttpUrl,
        method: String,
        headers: String,
    ): Request {
        val requestBuilder = Request.Builder().url(parsedUrl)
        when (method.uppercase()) {
            "HEAD" -> requestBuilder.head()
            "POST" -> requestBuilder.post(ByteArray(0).toRequestBody(null))
            else -> requestBuilder.get()
        }
        if (headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(headers).forEach { (k, v) ->
                val safeVal = HttpHeaderUtils.sanitizeHeaderValue(v)
                if (HttpHeaderUtils.isValidHeaderName(k)) {
                    requestBuilder.addHeader(k, safeVal)
                }
            }
        }
        return requestBuilder.build()
    }

    suspend fun checkServerHealth(serverUrl: String): PreCheckResult =
        withContext(Dispatchers.IO) {
            checkCustomServerHealth(serverUrl = serverUrl)
        }

    private fun clientCallCustom(
        request: Request,
        expectedStatus: Int,
        isCustomEndpoint: Boolean = false,
        explicitSecrets: List<String> = emptyList(),
    ): PreCheckResult =
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val code = response.code
                val isExpected = code == expectedStatus || (expectedStatus == 200 && response.isSuccessful)
                val isDefaultProbeAlive = !isCustomEndpoint && (code in listOf(200, 204, 401, 403, 404))
                if (isExpected || isDefaultProbeAlive) {
                    PreCheckResult.Passed
                } else {
                    PreCheckResult.Failed("HTTP status $code (expected $expectedStatus)")
                }
            }
        } catch (e: IOException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            PreCheckResult.Failed("Unreachable: $safeMsg")
        } catch (e: IllegalArgumentException) {
            val safeMsg = ExecutionLogFormatter.redactSecrets(e.message.orEmpty(), explicitSecrets)
            PreCheckResult.Failed("Unreachable: $safeMsg")
        }

    private fun collectSecrets(
        parsedUrl: okhttp3.HttpUrl,
        headers: String,
    ): List<String> {
        val secrets = mutableListOf<String>()
        for (name in parsedUrl.queryParameterNames) {
            parsedUrl.queryParameterValues(name).filterNotNull().forEach { v ->
                if (v.length >= 4) secrets.add(v)
            }
        }
        if (headers.isNotBlank()) {
            HttpHeaderUtils.parseHeaders(headers).forEach { (k, v) ->
                if (ExecutionLogFormatter.isSensitiveHeader(k) && v.isNotBlank()) {
                    secrets.add(v)
                }
            }
        }
        return secrets
    }
}
