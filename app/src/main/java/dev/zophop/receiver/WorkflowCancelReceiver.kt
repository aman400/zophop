package dev.zophop.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.zophop.data.WorkflowRepository
import dev.zophop.domain.engine.ActiveExecutionTracker
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.worker.WorkflowQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * BroadcastReceiver for handling notification Cancel action taps during active workflow execution.
 */
class WorkflowCancelReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReceiverEntryPoint {
        fun workflowRepository(): WorkflowRepository
    }

    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context?,
        intent: Intent?,
    ) {
        if (intent == null || context == null) return
        if (intent.action == "dev.zophop.ACTION_CANCEL_WORKFLOW") {
            val workflowId = intent.getStringExtra("workflow_id")
            val notificationId = intent.getIntExtra("notification_id", -1)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationId != -1) {
                nm?.cancel(notificationId)
            }
            val jobCancelled =
                if (!workflowId.isNullOrBlank()) {
                    nm?.cancel(workflowId.hashCode())
                    ExecutionJobRegistry.cancelJob(workflowId)
                } else {
                    ExecutionJobRegistry.cancelAllJobs()
                    false
                }

            val appContext = context.applicationContext ?: context
            val workflowRepository =
                EntryPointAccessors
                    .fromApplication(appContext, ReceiverEntryPoint::class.java)
                    .workflowRepository()

            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    if (jobCancelled) {
                        // Allow the cancelled coroutine's NonCancellable finalize block to persist step logs first,
                        // then clean up any lingering QUEUED/RUNNING rows for this workflow.
                        delay(150.milliseconds)
                    } else if (!workflowId.isNullOrBlank()) {
                        ActiveExecutionTracker.removeExecution(workflowId)
                    } else {
                        ActiveExecutionTracker.clearAll()
                    }
                    WorkflowQueueManager.cancelPendingLogsForWorkflow(
                        workflowRepository = workflowRepository,
                        workflowId = workflowId,
                        reason = "Execution cancelled from notification by user",
                    )
                } catch (_: Exception) {
                } finally {
                    pendingResult?.finish()
                }
            }
        }
    }
}
