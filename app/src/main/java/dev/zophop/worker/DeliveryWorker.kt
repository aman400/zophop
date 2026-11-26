package dev.zophop.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.zophop.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "DeliveryWorker"

@Suppress("ktlint:standard:annotation")
@HiltWorker
class DeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val forwardingManager: ForwardingManager,
    private val workflowQueueManager: WorkflowQueueManager,
    private val database: AppDatabase,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                forwardingManager.processPendingQueue()
                workflowQueueManager.processQueuedWorkflows()

                val pendingMessages = database.messageDao().getPendingMessages()
                val queuedWorkflowLogs = database.workflowDao().getQueuedWorkflowLogs()

                if (pendingMessages.isNotEmpty() || queuedWorkflowLogs.isNotEmpty()) {
                    Result.retry()
                } else {
                    Result.success()
                }
            } catch (e: IOException) {
                Log.w(TAG, "Delivery network error: ${e.message}", e)
                Result.retry()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Delivery state error: ${e.message}", e)
                Result.retry()
            } catch (e: SecurityException) {
                Log.w(TAG, "Delivery security error: ${e.message}", e)
                Result.retry()
            }
        }
}
