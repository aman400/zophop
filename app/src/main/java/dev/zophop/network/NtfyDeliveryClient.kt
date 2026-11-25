package dev.zophop.network

import dev.zophop.data.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

interface NtfyClient {
    suspend fun postMessage(
        serverUrl: String,
        topic: String,
        token: String,
        sender: String,
        body: String,
        allowInsecurePrivateHttp: Boolean = false,
        serverType: String = ServerType.NTFY,
    ): Result<Unit>
}

class OkHttpNtfyClient(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build(),
) : NtfyClient {
    override suspend fun postMessage(
        serverUrl: String,
        topic: String,
        token: String,
        sender: String,
        body: String,
        allowInsecurePrivateHttp: Boolean,
        serverType: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = serverUrl.trim().trimEnd('/')
                val cleanTopic = topic.trim().trimStart('/')

                if (baseUrl.isEmpty()) {
                    return@withContext Result.failure(IllegalArgumentException("Server URL is empty"))
                }

                val requestBuilder = Request.Builder()

                val safeToken = token.replace("\r", "").replace("\n", "").replace("\u0000", "").trim()

                when (serverType) {
                    ServerType.GOTIFY -> {
                        val encodedToken =
                            if (safeToken.isNotBlank()) {
                                java.net.URLEncoder.encode(safeToken, Charsets.UTF_8.name())
                            } else {
                                ""
                            }
                        val fullUrl =
                            if (encodedToken.isNotBlank()) {
                                "$baseUrl/message?token=$encodedToken"
                            } else {
                                "$baseUrl/message"
                            }

                        if (!UrlValidator.isValidUrl(fullUrl, allowInsecurePrivateHttp)) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Invalid URL or security policy restriction"),
                            )
                        }

                        requestBuilder.url(fullUrl)
                        if (safeToken.isNotBlank()) {
                            requestBuilder.header("X-Gotify-Key", safeToken)
                        }

                        val jsonPayload = """{"title":"${escapeJson(sender)}","message":"${escapeJson(body)}","priority":5}"""

                        requestBuilder.post(
                            jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()),
                        )
                    }

                    else -> { // Default: NTFY
                        if (cleanTopic.isEmpty()) {
                            return@withContext Result.failure(IllegalArgumentException("Topic is empty for ntfy"))
                        }

                        val fullUrl = "$baseUrl/$cleanTopic"
                        if (!UrlValidator.isValidUrl(fullUrl, allowInsecurePrivateHttp)) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Invalid URL or security policy restriction"),
                            )
                        }

                        requestBuilder.url(fullUrl)

                        if (safeToken.isNotBlank()) {
                            requestBuilder.header("Authorization", "Bearer $safeToken")
                        }

                        requestBuilder.header("Priority", "high")
                        requestBuilder.header("Tags", "key")

                        val isPrintableAsciiSender = sender.all { it.code in 32..126 }
                        val finalBodyText =
                            if (isPrintableAsciiSender && sender.isNotBlank()) {
                                requestBuilder.header("Title", sender)
                                body
                            } else {
                                if (sender.isNotBlank()) "From: $sender\n\n$body" else body
                            }

                        requestBuilder.post(
                            finalBodyText.toRequestBody("text/plain; charset=utf-8".toMediaType()),
                        )
                    }
                }

                val request = requestBuilder.build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success(Unit)
                    } else {
                        Result.failure(IOException("HTTP error code: ${response.code}"))
                    }
                }
            } catch (e: IOException) {
                Result.failure(IOException("Delivery failed: ${e.javaClass.simpleName}"))
            } catch (e: IllegalArgumentException) {
                Result.failure(IOException("Delivery failed: ${e.javaClass.simpleName}"))
            } catch (e: IllegalStateException) {
                Result.failure(IOException("Delivery failed: ${e.javaClass.simpleName}"))
            }
        }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
