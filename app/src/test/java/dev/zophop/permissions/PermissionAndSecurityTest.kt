package dev.zophop.permissions

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import dev.zophop.plugin.sms.SendSmsExecutor
import dev.zophop.plugin.sms.SmsTriggerExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class PermissionAndSecurityTest {
    private lateinit var context: Application

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSmsTriggerFailsWhenReceiveSmsPermissionMissing() =
        runTest {
            val shadowApp = Shadows.shadowOf(context)
            shadowApp.denyPermissions(Manifest.permission.RECEIVE_SMS)

            val executor = SmsTriggerExecutor()
            val node = WorkflowNode("n_sms", "SMS Trigger", NodeType.SMS_TRIGGER)
            val execContext = ExecutionContext(mapOf("sender" to "BANK", "body" to "OTP 1234"))

            val result = executor.execute(node, execContext, context)

            assertTrue(result is NodeExecutionResult.Failed)
            val reason = (result as NodeExecutionResult.Failed).reason
            assertTrue(reason.contains("RECEIVE_SMS") || reason.contains("permission"))
        }

    @Test
    fun testSmsTriggerPassesWhenReceiveSmsPermissionGranted() =
        runTest {
            val shadowApp = Shadows.shadowOf(context)
            shadowApp.grantPermissions(Manifest.permission.RECEIVE_SMS)

            val executor = SmsTriggerExecutor()
            val node = WorkflowNode("n_sms", "SMS Trigger", NodeType.SMS_TRIGGER, configJson = """{"allowlist":".*"}""")
            val execContext = ExecutionContext(mapOf("sender" to "BANK", "body" to "OTP 1234"))

            val result = executor.execute(node, execContext, context)

            assertTrue(result is NodeExecutionResult.Success)
        }

    @Test
    fun testSendSmsFailsWhenSendSmsPermissionMissing() =
        runTest {
            val shadowApp = Shadows.shadowOf(context)
            shadowApp.denyPermissions(Manifest.permission.SEND_SMS)

            val executor = SendSmsExecutor()
            val node =
                WorkflowNode(
                    id = "n_send_sms",
                    name = "Send SMS Action",
                    type = NodeType.SEND_SMS_ACTION,
                    configJson = """{"recipient":"+15551234567","body":"Alert Message"}""",
                )
            val execContext = ExecutionContext(emptyMap())

            val result = executor.execute(node, execContext, context)

            assertTrue(result is NodeExecutionResult.Failed)
            val reason = (result as NodeExecutionResult.Failed).reason
            assertTrue(reason.contains("SEND_SMS") || reason.contains("permission"))
        }

    @Test
    fun testGpsPreconditionFailsWhenLocationPermissionMissing() =
        runTest {
            val shadowApp = Shadows.shadowOf(context)
            shadowApp.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

            val evaluator = PreconditionEvaluator(context = context)
            val gpsPrecondition = Precondition.GpsCheck(id = "prec_gps", isEnabled = true)

            val result = evaluator.evaluate(gpsPrecondition)

            assertTrue(result is PreconditionResult.Failed)
            val reason = (result as PreconditionResult.Failed).reason
            assertTrue(
                reason.contains("LOCATION") || reason.contains("permission") || reason.contains("Gps") || reason.contains("Fine Location"),
            )
        }

    @Test
    fun testSimPreconditionFailsWhenPhoneStatePermissionMissing() =
        runTest {
            val shadowApp = Shadows.shadowOf(context)
            shadowApp.denyPermissions(Manifest.permission.READ_PHONE_STATE)

            val evaluator = PreconditionEvaluator(context = context)
            val simPrecondition = Precondition.SimCheck(id = "prec_sim", isEnabled = true)

            val result = evaluator.evaluate(simPrecondition)

            assertTrue(result is PreconditionResult.Failed)
            val reason = (result as PreconditionResult.Failed).reason
            assertTrue(reason.contains("PHONE") || reason.contains("permission") || reason.contains("Sim") || reason.contains("State"))
        }
}
