package dev.zophop.domain.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionJobRegistryTest {
    @Test
    fun testRegisterAndCancelJob() =
        runTest {
            val workflowId = "wf-test-101"
            val job =
                launch {
                    delay(10000L)
                }

            ExecutionJobRegistry.registerJob(workflowId, job)
            assertTrue(ExecutionJobRegistry.isJobRunning(workflowId))

            val cancelled = ExecutionJobRegistry.cancelJob(workflowId)
            assertTrue(cancelled)
            assertFalse(ExecutionJobRegistry.isJobRunning(workflowId))
            assertTrue(job.isCancelled)
        }

    @Test
    fun testCancelAllJobs() =
        runTest {
            val job1 = launch { delay(10000L) }
            val job2 = launch { delay(10000L) }

            ExecutionJobRegistry.registerJob("wf-1", job1)
            ExecutionJobRegistry.registerJob("wf-2", job2)

            assertTrue(ExecutionJobRegistry.isJobRunning("wf-1"))
            assertTrue(ExecutionJobRegistry.isJobRunning("wf-2"))

            ExecutionJobRegistry.cancelAllJobs()

            assertFalse(ExecutionJobRegistry.isJobRunning("wf-1"))
            assertFalse(ExecutionJobRegistry.isJobRunning("wf-2"))
            assertTrue(job1.isCancelled)
            assertTrue(job2.isCancelled)
        }
}
