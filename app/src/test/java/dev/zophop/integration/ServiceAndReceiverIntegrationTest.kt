package dev.zophop.integration

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.receiver.SmsReceiver
import dev.zophop.receiver.WorkflowCancelReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ServiceAndReceiverIntegrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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
                kotlinx.coroutines.delay(2000L)
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
                kotlinx.coroutines.delay(5000)
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
}
