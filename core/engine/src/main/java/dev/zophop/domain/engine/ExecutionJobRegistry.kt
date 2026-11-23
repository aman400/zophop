package dev.zophop.domain.engine

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for tracking and cancelling active workflow execution jobs.
 */
object ExecutionJobRegistry {
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * Registers an active execution job for a workflow ID.
     */
    fun registerJob(
        workflowId: String,
        job: Job,
    ) {
        activeJobs[workflowId] = job
        job.invokeOnCompletion {
            activeJobs.remove(workflowId)
        }
    }

    /**
     * Cancels an active execution job for a workflow ID if currently running.
     */
    fun cancelJob(workflowId: String): Boolean {
        val job = activeJobs.remove(workflowId)
        return if (job != null && job.isActive) {
            job.cancel()
            true
        } else {
            false
        }
    }

    /**
     * Returns true if an execution job is currently active for a workflow ID.
     */
    fun isJobRunning(workflowId: String): Boolean = activeJobs[workflowId]?.isActive == true

    /**
     * Cancels all active workflow execution jobs.
     */
    fun cancelAllJobs() {
        activeJobs.values.forEach { if (it.isActive) it.cancel() }
        activeJobs.clear()
    }
}
