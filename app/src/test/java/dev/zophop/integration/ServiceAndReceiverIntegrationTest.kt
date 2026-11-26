package dev.zophop.integration

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.receiver.SmsReceiver
import dev.zophop.receiver.WorkflowCancelReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = HiltTestApplication::class)
class ServiceAndReceiverIntegrationTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.RECEIVE_SMS)
    }

    @After
    fun tearDown() {
        ExecutionJobRegistry.cancelAllJobs()
    }

    @Test
    fun testExecutionJobRegistryTrackingAndCancellation() {
        val workflowId = "test_workflow_123"
        val job =
            CoroutineScope(Dispatchers.IO).launch {
                // Keep job active briefly
                kotlinx.coroutines.delay(2000L.milliseconds)
            }

        ExecutionJobRegistry.registerJob(workflowId, job)
        assertTrue(job.isActive)

        ExecutionJobRegistry.cancelJob(workflowId)
        assertTrue(job.isCancelled)
    }

    @Test
    fun testWorkflowCancelReceiverHandling() {
        val workflowId = "workflow_cancel_target"
        val job =
            CoroutineScope(Dispatchers.IO).launch {
                kotlinx.coroutines.delay(5000.milliseconds)
            }
        ExecutionJobRegistry.registerJob(workflowId, job)
        assertTrue(job.isActive)

        val receiver = WorkflowCancelReceiver()
        val intent =
            Intent("dev.zophop.ACTION_CANCEL_WORKFLOW").apply {
                putExtra("workflow_id", workflowId)
                putExtra("notification_id", 456)
            }

        receiver.onReceive(context, intent)
        assertTrue(job.isCancelled)
    }

    @Test
    fun testSmsReceiverIntentHandlingIgnoredWhenWrongAction() {
        val receiver = SmsReceiver()
        val intent = Intent("android.intent.action.BOOT_COMPLETED")
        // Should not throw or crash
        receiver.onReceive(context, intent)
    }

    @Test
    fun testSmsReceiverExecutesSmsTriggerWorkflowEndToEnd() {
        val db = dev.zophop.data.AppDatabase.getInstance(context)
        val workflowRepo = dev.zophop.data.RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())

        val triggerNode = dev.zophop.domain.model.WorkflowNode(
            id = "sms_trigger_1",
            name = "Incoming SMS Trigger",
            type = dev.zophop.domain.model.NodeType.SMS_TRIGGER,
            configJson = """{"allowlist":".*","keywordRegex":".*"}""",
        )
        val actionNode = dev.zophop.domain.model.WorkflowNode(
            id = "format_action_1",
            name = "Format Message",
            type = dev.zophop.domain.model.NodeType.TEMPLATE_FORMATTER,
            configJson = """{"template":"Received from ${'$'}{sender}: ${'$'}{body}"}""",
        )
        val connection = dev.zophop.domain.model.WorkflowConnection("sms_trigger_1", "format_action_1")

        val workflow = dev.zophop.domain.model.Workflow(
            id = "wf_sms_e2e",
            name = "SMS Automation Workflow",
            isEnabled = true,
            nodes = listOf(triggerNode, actionNode),
            connections = listOf(connection),
        )

        kotlinx.coroutines.runBlocking {
            workflowRepo.saveWorkflow(workflow)
        }

        val receiver = SmsReceiver()
        val intent = Intent("android.provider.Telephony.SMS_RECEIVED").apply {
            putExtra("sender", "HDFC_BANK")
            putExtra("body", "Your OTP is 123456")
        }

        receiver.onReceive(context, intent)

        // Give background coroutine in goAsync a moment to run and finish
        kotlinx.coroutines.runBlocking {
            val deadline = System.currentTimeMillis() + 10_000L
            var lastLog: dev.zophop.data.WorkflowExecutionLogEntity? = null
            while (System.currentTimeMillis() < deadline) {
                val logs = db.workflowDao().getLogsForWorkflowFlow("wf_sms_e2e").first()
                if (logs.isNotEmpty() && logs.first().status != "RUNNING") {
                    lastLog = logs.first()
                    break
                }
                kotlinx.coroutines.delay(50.milliseconds)
            }
            assertTrue("Logs should be recorded for the executed SMS workflow", lastLog != null)
            assertTrue(
                "Workflow execution status: ${lastLog?.status}, reason: ${lastLog?.errorReason}, logs: ${lastLog?.logs}",
                lastLog?.status == "SUCCESS",
            )
        }
    }

    @Test
    fun testZopHopNotificationListenerServiceHandlesNullOrSelfNotificationGracefully() {
        val service = dev.zophop.service.ZopHopNotificationListenerService()
        // Calling onNotificationPosted with null should not throw
        service.onNotificationPosted(null)
        service.onDestroy()
    }

    @Test
    fun testSmsForwarderServiceStartReturnsFalseOnBackgroundStartRestriction() {
        val restrictedContext =
            object : android.content.ContextWrapper(context) {
                override fun startForegroundService(service: Intent?): android.content.ComponentName? =
                    error("startForegroundService not allowed from background")
            }
        val started = dev.zophop.service.SmsForwarderService.startService(restrictedContext)
        assertFalse("startService must handle background FGS restriction gracefully", started)
    }

    @Test
    fun testBootReceiverHandlesBootCompletedSafelyWithoutThrowing() {
        val repo = dev.zophop.data.EncryptedSettingsRepository(context)
        repo.saveSettings(dev.zophop.data.AppSettings(isEnabled = false, globalWorkflowsEnabled = true))

        val receiver = dev.zophop.receiver.BootReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)
    }
}
