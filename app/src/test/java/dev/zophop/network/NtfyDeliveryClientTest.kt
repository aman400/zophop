package dev.zophop.network

import dev.zophop.data.ServerType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyDeliveryClientTest {
    @Test
    fun testSuccessfulDeliveryWithAsciiSender() =
        runTest {
            var capturedUrl = ""
            var capturedAuthHeader = ""
            var capturedTitleHeader = ""
            var capturedPriorityHeader = ""
            var capturedTagsHeader = ""
            var capturedBody = ""

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedUrl = request.url.toString()
                        capturedAuthHeader = request.header("Authorization") ?: ""
                        capturedTitleHeader = request.header("Title") ?: ""
                        capturedPriorityHeader = request.header("Priority") ?: ""
                        capturedTagsHeader = request.header("Tags") ?: ""

                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        capturedBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val client = OkHttpNtfyClient(okHttpClient)
            val result =
                client.postMessage(
                    serverUrl = "https://ntfy.sh",
                    topic = "my-otp",
                    token = "secret-token",
                    sender = "HDFCBK",
                    body = "Your OTP is 123456",
                )

            assertTrue(result.isSuccess)
            assertEquals("https://ntfy.sh/my-otp", capturedUrl)
            assertEquals("Bearer secret-token", capturedAuthHeader)
            assertEquals("HDFCBK", capturedTitleHeader)
            assertEquals("high", capturedPriorityHeader)
            assertEquals("key", capturedTagsHeader)
            assertEquals("Your OTP is 123456", capturedBody)
        }

    @Test
    fun testGotifyDeliverySuccess() =
        runTest {
            var capturedUrl = ""
            var capturedGotifyHeader = ""
            var capturedBody = ""

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedUrl = request.url.toString()
                        capturedGotifyHeader = request.header("X-Gotify-Key") ?: ""

                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        capturedBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val client = OkHttpNtfyClient(okHttpClient)
            val result =
                client.postMessage(
                    serverUrl = "https://gotify.example.com",
                    topic = "",
                    token = "gotify-app-token",
                    sender = "HDFCBK",
                    body = "Your OTP is 987654",
                    serverType = ServerType.GOTIFY,
                )

            assertTrue(result.isSuccess)
            assertEquals("https://gotify.example.com/message?token=gotify-app-token", capturedUrl)
            assertEquals("gotify-app-token", capturedGotifyHeader)

            assertTrue(capturedBody.contains("\"title\":\"HDFCBK\""))
            assertTrue(capturedBody.contains("\"message\":\"Your OTP is 987654\""))
            assertTrue(capturedBody.contains("\"priority\":5"))
        }

    @Test
    fun testNonAsciiSenderPutsSenderInBody() =
        runTest {
            var capturedTitleHeader: String? = null
            var capturedBody = ""

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedTitleHeader = request.header("Title")

                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        capturedBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val client = OkHttpNtfyClient(okHttpClient)
            val result =
                client.postMessage(
                    serverUrl = "https://ntfy.sh",
                    topic = "my-otp",
                    token = "secret-token",
                    sender = "SENDER-😊",
                    body = "Your OTP is 123456",
                )

            assertTrue(result.isSuccess)
            assertEquals(null, capturedTitleHeader)
            assertTrue(capturedBody.contains("From: SENDER-😊"))
            assertTrue(capturedBody.contains("Your OTP is 123456"))
        }

    @Test
    fun testHttpErrorReturnsFailure() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(500)
                            .message("Internal Error")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val client = OkHttpNtfyClient(okHttpClient)
            val result =
                client.postMessage(
                    serverUrl = "https://ntfy.sh",
                    topic = "my-otp",
                    token = "",
                    sender = "HDFCBK",
                    body = "Your OTP is 123456",
                )

            assertTrue(result.isFailure)
        }

    @Test
    fun testInsecurePublicHttpRejected() =
        runTest {
            val client = OkHttpNtfyClient()
            val result =
                client.postMessage(
                    serverUrl = "http://ntfy.sh",
                    topic = "my-otp",
                    token = "",
                    sender = "HDFCBK",
                    body = "Your OTP is 123456",
                )

            assertTrue(result.isFailure)
        }

    @Test
    fun testControlCharsInSenderMoveSenderToBodyInsteadOfCrashingTitleHeader() =
        runTest {
            var capturedTitleHeader: String? = "INITIAL"
            var capturedBody = ""

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedTitleHeader = request.header("Title")

                        val buffer = okio.Buffer()
                        request.body?.writeTo(buffer)
                        capturedBody = buffer.readUtf8()

                        Response
                            .Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val client = OkHttpNtfyClient(okHttpClient)
            val result =
                client.postMessage(
                    serverUrl = "https://ntfy.sh",
                    topic = "my-otp",
                    token = "tok\r\nEvil: 1",
                    sender = "BANK\r\nInjected: bad",
                    body = "Your OTP is 123456",
                )

            assertTrue(result.isSuccess)
            assertEquals(null, capturedTitleHeader)
            assertTrue(capturedBody.contains("Your OTP is 123456"))
        }

    @Test
    fun testGotifyTokenWithSpecialCharsIsUrlEncodedAndSanitized() =
        runTest {
            var capturedUrl = ""
            var capturedHeader = ""

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        capturedUrl = request.url.toString()
                        capturedHeader = request.header("X-Gotify-Key") ?: ""

                        Response
                            .Builder()
                            .request(request)
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val client = OkHttpNtfyClient(okHttpClient)
            val result =
                client.postMessage(
                    serverUrl = "https://gotify.example.com",
                    topic = "",
                    token = "tok&admin=1\r\n",
                    sender = "HDFCBK",
                    body = "Body",
                    serverType = ServerType.GOTIFY,
                )

            assertTrue(result.isSuccess)
            assertEquals("https://gotify.example.com/message?token=tok%26admin%3D1", capturedUrl)
            assertEquals("tok&admin=1", capturedHeader)
        }
}
