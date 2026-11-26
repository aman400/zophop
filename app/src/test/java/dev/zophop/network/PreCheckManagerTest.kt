package dev.zophop.network

import dev.zophop.data.DeliveryNode
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test

class PreCheckManagerTest {
    @Test
    fun testServerHealthcheckSuccess() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{\"status\":\"pass\"}".toResponseBody())
                            .build()
                    }.build()

            val manager = PreCheckManager(context = null, okHttpClient = okHttpClient)
            val result = manager.checkServerHealth("https://ntfy.sh")
            assertTrue(result is PreCheckResult.Passed)
        }

    @Test
    fun testServerHealthcheckErrorReturnsFailed() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(503)
                            .message("Service Unavailable")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val manager = PreCheckManager(context = null, okHttpClient = okHttpClient)
            val result = manager.checkServerHealth("https://ntfy.sh")
            assertTrue(result is PreCheckResult.Failed)
            val failed = result as PreCheckResult.Failed
            assertTrue(failed.reason.contains("503"))
        }

    @Test
    fun testNodeLevelPreChecksVerification() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val manager = PreCheckManager(context = null, okHttpClient = okHttpClient)

            val node =
                DeliveryNode(
                    name = "Test Node",
                    serverUrl = "https://ntfy.sh",
                    requireInternet = false,
                    requireVpn = false,
                    requireHealthcheck = true,
                )

            val result = manager.verifyNodePreChecks(node)
            assertTrue(result is PreCheckResult.Passed)
        }

    @Test
    fun testCustomHealthcheckEndpoint_404_failsWhenExpecting200() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val manager = PreCheckManager(context = null, okHttpClient = okHttpClient)
            val result =
                manager.checkCustomServerHealth(
                    serverUrl = "https://my-server.com",
                    customUrl = "https://my-server.com/custom/health",
                    expectedStatus = 200,
                )

            assertTrue("Custom endpoint returning 404 must fail when expecting 200", result is PreCheckResult.Failed)
            assertTrue((result as PreCheckResult.Failed).reason.contains("404"))
        }

    @Test
    fun testCustomHealthcheckEndpoint_matchesCustomExpectedStatus() =
        runTest {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(204)
                            .message("No Content")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val manager = PreCheckManager(context = null, okHttpClient = okHttpClient)
            val result =
                manager.checkCustomServerHealth(
                    serverUrl = "https://my-server.com",
                    customUrl = "https://my-server.com/healthz",
                    expectedStatus = 204,
                )

            assertTrue("Custom endpoint returning expected 204 must pass", result is PreCheckResult.Passed)
        }

    @Test
    fun testDefaultServerProbe_404_passesLivenessCheck() =
        runTest {
            // Without a customUrl (default /health probe), a 404 indicates the host is alive
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(okhttp3.Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("".toResponseBody())
                            .build()
                    }.build()

            val manager = PreCheckManager(context = null, okHttpClient = okHttpClient)
            val result = manager.checkServerHealth("https://ntfy.sh")
            assertTrue("Generic server probe 404 confirms host reachability", result is PreCheckResult.Passed)
        }
}
