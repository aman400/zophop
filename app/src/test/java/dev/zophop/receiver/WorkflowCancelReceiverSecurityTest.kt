package dev.zophop.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dev.zophop.domain.engine.ExecutionJobRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Security audit test suite analyzing IPC and Intent broadcast handling in [WorkflowCancelReceiver].
 *
 * Verifies vulnerability behavior:
 * - Denial of Service: Unprivileged external intents with missing workflow_id trigger cancelAllJobs()
 * - Target cancellation: Explicit cancellation of specific workflow IDs
 * - Safety on invalid actions, null intents, and malformed extras
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, application = HiltTestApplication::class)
class WorkflowCancelReceiverSecurityTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private lateinit var context: Context
    private lateinit var receiver: WorkflowCancelReceiver

    @Before
    fun setUp() {
        hiltRule.inject()
        context = ApplicationProvider.getApplicationContext()
        receiver = WorkflowCancelReceiver()
        ExecutionJobRegistry.cancelAllJobs()
    }

    @After
    fun tearDown() {
        ExecutionJobRegistry.cancelAllJobs()
    }

    @Test
    fun testBroadcastReceiver_nullIntentOrContextHandledSafely() {
        // Must not throw or crash on null inputs
        receiver.onReceive(null, null)
        receiver.onReceive(context, null)
        receiver.onReceive(null, Intent("dev.zophop.ACTION_CANCEL_WORKFLOW"))
    }

    @Test
    fun testBroadcastReceiver_ignoresIrrelevantActions() {
        val job = CoroutineScope(Dispatchers.IO).launch { kotlinx.coroutines.delay(5000) }
        ExecutionJobRegistry.registerJob("wf_active", job)
        assertTrue(job.isActive)

        // Foreign action broadcast should not cancel active jobs
        val intent = Intent("android.intent.action.BATTERY_LOW")
        receiver.onReceive(context, intent)

        assertTrue(job.isActive)
    }

    @Test
    fun testSecurityAudit_unprivilegedBroadcastWithoutWorkflowIdCancelsAllJobs() {
        // Threat Model Analysis:
        // Any 3rd party app broadcasting "dev.zophop.ACTION_CANCEL_WORKFLOW" without parameters
        // causes ExecutionJobRegistry.cancelAllJobs() to be called.
        val job1 = CoroutineScope(Dispatchers.IO).launch { kotlinx.coroutines.delay(5000) }
        val job2 = CoroutineScope(Dispatchers.IO).launch { kotlinx.coroutines.delay(5000) }

        ExecutionJobRegistry.registerJob("wf_alpha", job1)
        ExecutionJobRegistry.registerJob("wf_beta", job2)

        assertTrue(job1.isActive)
        assertTrue(job2.isActive)

        // Malicious or unprivileged broadcast sent with no workflow_id extra:
        val spoofedBroadcast = Intent("dev.zophop.ACTION_CANCEL_WORKFLOW")
        receiver.onReceive(context, spoofedBroadcast)

        // Verifying vulnerability impact: Both running workflows were forcibly killed!
        assertTrue("Job 1 was cancelled by external broadcast", job1.isCancelled)
        assertTrue("Job 2 was cancelled by external broadcast", job2.isCancelled)
    }

    @Test
    fun testBroadcastReceiver_targetedCancellationOnlyCancelsSpecifiedWorkflow() {
        val targetJob = CoroutineScope(Dispatchers.IO).launch { kotlinx.coroutines.delay(5000) }
        val otherJob = CoroutineScope(Dispatchers.IO).launch { kotlinx.coroutines.delay(5000) }

        ExecutionJobRegistry.registerJob("wf_target", targetJob)
        ExecutionJobRegistry.registerJob("wf_other", otherJob)

        val targetedIntent = Intent("dev.zophop.ACTION_CANCEL_WORKFLOW").apply {
            putExtra("workflow_id", "wf_target")
            putExtra("notification_id", 101)
        }

        receiver.onReceive(context, targetedIntent)

        assertTrue("Targeted job was cancelled", targetJob.isCancelled)
        assertTrue("Unrelated job remains active", otherJob.isActive)
    }
}
