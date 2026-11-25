package dev.zophop.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zophop.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "DeliveryWorker"

class DeliveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            try {
                val forwardingManager = ForwardingManager.getInstance(applicationContext)
                forwardingManager.processPendingQueue()
                WorkflowQueueManager.processQueuedWorkflows(applicationContext)

                val pendingMessages =
                    AppDatabase
                        .getInstance(applicationContext)
                        .messageDao()
                        .getPendingMessages()
                val queuedWorkflowLogs =
                    AppDatabase
                        .getInstance(applicationContext)
                        .workflowDao()
                        .getQueuedWorkflowLogs()

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
