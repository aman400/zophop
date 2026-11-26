package dev.zophop.domain.engine

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for tracking and cancelling active workflow execution jobs.
 */
object ExecutionJobRegistry {
    private val activeJobs = ConcurrentHashMap<String, MutableSet<Job>>()

    /**
     * Registers an active execution job for a workflow ID.
     */
    fun registerJob(
        workflowId: String,
        job: Job,
    ) {
        val jobSet = activeJobs.computeIfAbsent(workflowId) { ConcurrentHashMap.newKeySet() }
        jobSet.add(job)
        job.invokeOnCompletion {
            activeJobs.computeIfPresent(workflowId) { _, existing ->
                existing.remove(job)
                if (existing.isEmpty()) null else existing
            }
        }
    }

    /**
     * Cancels all active execution jobs for a workflow ID if currently running.
     */
    fun cancelJob(workflowId: String): Boolean {
        val jobs = activeJobs.remove(workflowId) ?: return false
        var cancelledAny = false
        for (job in jobs) {
            if (job.isActive) {
                job.cancel()
                cancelledAny = true
            }
        }
        return cancelledAny
    }

    /**
     * Returns true if any execution job is currently active for a workflow ID.
     */
    fun isJobRunning(workflowId: String): Boolean = activeJobs[workflowId]?.any { it.isActive } == true

    /**
     * Cancels all active workflow execution jobs.
     */
    fun cancelAllJobs() {
        val keys = activeJobs.keys.toList()
        for (key in keys) {
            cancelJob(key)
        }
    }
}
