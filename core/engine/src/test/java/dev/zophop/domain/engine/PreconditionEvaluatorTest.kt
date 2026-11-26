package dev.zophop.domain.engine

import dev.zophop.domain.model.Precondition
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionEvaluatorTest {
    @Test
    fun testHealthcheckPreconditionSuccess() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{\"status\":\"ok\"}".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                )

            val result = evaluator.evaluate(healthCheck)
            assertTrue(result is PreconditionResult.Passed)
        }

    @Test
    fun testHealthcheckPreconditionFailure() =
        runTest {
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(500)
                            .message("Internal Error")
                            .body("error".toResponseBody())
                            .build()
                    }.build()

            val evaluator = PreconditionEvaluator(context = null, okHttpClient = client)
            val healthCheck =
                Precondition.HealthCheck(
                    isEnabled = true,
                    targetUrl = "https://example.com/health",
                    expectedStatus = 200,
                )

            val result = evaluator.evaluate(healthCheck)
            assertTrue(result is PreconditionResult.Failed)
            val failed = result as PreconditionResult.Failed
            assertTrue(failed.reason.contains("500"))
        }
}
