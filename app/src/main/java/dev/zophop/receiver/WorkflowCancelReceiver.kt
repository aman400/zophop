package dev.zophop.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zophop.domain.engine.ExecutionJobRegistry

/**
 * BroadcastReceiver for handling notification Cancel action taps during active workflow execution.
 */
class WorkflowCancelReceiver : BroadcastReceiver() {
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
            if (!workflowId.isNullOrBlank()) {
                nm?.cancel(workflowId.hashCode())
                ExecutionJobRegistry.cancelJob(workflowId)
            } else {
                ExecutionJobRegistry.cancelAllJobs()
            }
        }
    }
}
