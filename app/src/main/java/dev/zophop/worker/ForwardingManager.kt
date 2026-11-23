package dev.zophop.worker

import android.content.Context
import android.database.SQLException
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.zophop.data.AppDatabase
import dev.zophop.data.AppSettings
import dev.zophop.data.DeliveryNode
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.MessageDao
import dev.zophop.data.MessageEntity
import dev.zophop.data.MessageStatus
import dev.zophop.data.SettingsRepository
import dev.zophop.filter.MessageTemplateParser
import dev.zophop.network.NtfyClient
import dev.zophop.network.OkHttpNtfyClient
import dev.zophop.network.PreCheckManager
import dev.zophop.network.PreCheckResult
import dev.zophop.service.SmsForwarderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class ForwardingManager(
    private val messageDao: MessageDao,
    private val settingsRepository: SettingsRepository,
    private val ntfyClient: NtfyClient = OkHttpNtfyClient(),
    private val workManager: WorkManager? = null,
    private val preCheckManager: PreCheckManager? = null,
    private val delayProvider: suspend (Long) -> Unit = { delay(it.milliseconds) },
    private val context: Context? = null,
) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var isPollingActive = false
    private var pollingJob: Job? = null

    suspend fun enqueueIncomingSms(
        sender: String,
        body: String,
    ): MessageEntity =
        withContext(Dispatchers.IO) {
            val entity =
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sender = sender,
                    body = body,
                    receivedAt = System.currentTimeMillis(),
                    status = MessageStatus.QUEUED,
                    retryCount = 0,
                )
            messageDao.insertMessage(entity)
            messageDao.purgeOldEvents(50)

            // Trigger queue processing immediately
            processPendingQueue()
            entity
        }

    suspend fun processPendingQueue(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val settings = settingsRepository.getSettings()

                    // Run auto-clear if enabled
                    if (settings.autoClearEnabled && settings.autoClearMinutes > 0) {
                        val cutoff = System.currentTimeMillis() - (settings.autoClearMinutes * 60 * 1000L)
                        messageDao.deleteMessagesOlderThan(cutoff)
                    }

                    if (!settings.isEnabled) {
                        return@withLock
                    }

                    val pending = messageDao.getPendingMessages()
                    if (pending.isEmpty()) {
                        return@withLock
                    }

                    val activeNodes = settings.activeNodes.filter { it.isEnabled && it.serverUrl.isNotBlank() }
                    if (activeNodes.isEmpty()) {
                        return@withLock
                    }

                    val now = System.currentTimeMillis()
                    val expiryMillis = settings.expiryMinutes * 60 * 1000L

                    var hasUnsentFailures = false

                    for (msg in pending) {
                        if (processSinglePendingMessage(msg, settings, now, expiryMillis, activeNodes)) {
                            hasUnsentFailures = true
                        }
                    }

                    if (hasUnsentFailures) {
                        startActiveQueuePolling()
                        if (workManager != null) {
                            scheduleWorkManagerRetry()
                        }
                    }
                } catch (e: SQLException) {
                    Log.e(TAG, "Database error in processPendingQueue", e)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "State error in processPendingQueue", e)
                } catch (e: IOException) {
                    Log.e(TAG, "IO error in processPendingQueue", e)
                }
            }
        }

    private suspend fun processSinglePendingMessage(
        msg: MessageEntity,
        settings: AppSettings,
        now: Long,
        expiryMillis: Long,
        activeNodes: List<DeliveryNode>,
    ): Boolean {
        val current = isMessageValidForDelivery(
            messageDao.getMessageById(msg.id),
            now,
            expiryMillis,
            settings.expiryMinutes,
        ) ?: return false

        val formattedPayload = MessageTemplateParser.formatPayload(
            sender = current.sender,
            body = current.body.orEmpty(),
            senderRegex = settings.senderRegex,
            bodyParseRegex = settings.bodyParseRegex,
            template = settings.customMessageTemplate,
        )

        val deliveryResult = deliverToNodes(current, formattedPayload, activeNodes, settings.allowInsecureHttp)
        return if (deliveryResult.allDelivered) {
            handleDeliverySuccess(current)
            false
        } else {
            handleDeliveryFailure(current, settings.maxRetries, deliveryResult)
            true
        }
    }

    private suspend fun isMessageValidForDelivery(
        current: MessageEntity?,
        now: Long,
        expiryMillis: Long,
        expiryMinutes: Int,
    ): MessageEntity? {
        if (current == null || current.status == MessageStatus.DELIVERED || current.status == MessageStatus.EXPIRED) {
            return null
        }
        if (now - current.receivedAt > expiryMillis || current.body == null) {
            val note = if (current.body == null) null else "Expired after $expiryMinutes minutes"
            messageDao.updateMessage(current.copy(status = MessageStatus.EXPIRED, body = null, errorMessage = note))
            return null
        }
        return current
    }

    private suspend fun deliverToNodes(
        current: MessageEntity,
        formattedPayload: String,
        activeNodes: List<DeliveryNode>,
        allowInsecureHttp: Boolean,
    ): DeliveryResult {
        var allNodesDelivered = true
        val nodeNotes = mutableListOf<String>()
        val preCheckFailures = mutableListOf<String>()

        for (node in activeNodes) {
            if (preCheckManager != null) {
                val nodeCheck = preCheckManager.verifyNodePreChecks(node)
                if (nodeCheck is PreCheckResult.Failed) {
                    allNodesDelivered = false
                    val failureDesc = "${node.name} (${nodeCheck.reason})"
                    nodeNotes.add("${node.name} throttled: ${nodeCheck.reason}")
                    preCheckFailures.add(failureDesc)
                    continue
                }
            }

            context?.let { ctx ->
                SmsForwarderService.updateNotificationStatus(
                    ctx,
                    "🚀 Executing Forwarding Trigger",
                    "Forwarding SMS from ${current.sender} to ${node.name}...",
                )
            }

            val nodeDelivered = attemptNodeDelivery(node, current.sender, formattedPayload, allowInsecureHttp, nodeNotes)
            if (!nodeDelivered) {
                allNodesDelivered = false
            }
        }

        return DeliveryResult(allNodesDelivered, nodeNotes, preCheckFailures)
    }

    private suspend fun attemptNodeDelivery(
        node: DeliveryNode,
        sender: String,
        payload: String,
        allowInsecureHttp: Boolean,
        nodeNotes: MutableList<String>,
    ): Boolean {
        val maxImmediateAttempts = 4
        for (attempt in 1..maxImmediateAttempts) {
            val result = ntfyClient.postMessage(
                serverUrl = node.serverUrl,
                topic = node.topic,
                token = node.token,
                sender = sender,
                body = payload,
                allowInsecurePrivateHttp = allowInsecureHttp,
                serverType = node.serverType,
            )

            if (result.isSuccess) {
                return true
            }
            val errMsg = result.exceptionOrNull()?.message ?: "Delivery failure"
            nodeNotes.add("${node.name}: $errMsg")
            if (attempt < maxImmediateAttempts) {
                delayProvider(15_000L)
            }
        }
        return false
    }

    private suspend fun handleDeliverySuccess(current: MessageEntity) {
        val deliveredEntity = current.copy(
            status = MessageStatus.DELIVERED,
            body = null, // Delete body immediately on delivery
            retryCount = current.retryCount + 1,
            errorMessage = null,
        )
        messageDao.updateMessage(deliveredEntity)

        context?.let { ctx ->
            SmsForwarderService.updateNotificationStatus(
                ctx,
                "✅ Forwarding Trigger Executed",
                "Successfully delivered SMS from ${current.sender} to target nodes",
            )
            delayProvider(4000L)
            SmsForwarderService.resetToDefaultStatus(ctx)
        }
    }

    private suspend fun handleDeliveryFailure(
        current: MessageEntity,
        maxRetries: Int,
        result: DeliveryResult,
    ) {
        val isThrottled = result.preCheckFailures.isNotEmpty()
        val newRetryCount = if (isThrottled) current.retryCount else current.retryCount + 1

        val (updatedStatus, noteText) = if (isThrottled) {
            MessageStatus.QUEUED to "⏳ Waiting for Pre-Conditions: " + result.preCheckFailures.joinToString("; ")
        } else if (newRetryCount >= maxRetries) {
            MessageStatus.FAILED to "Failed after $maxRetries retries: " + result.nodeNotes.joinToString("; ")
        } else {
            MessageStatus.QUEUED to "Retry attempt $newRetryCount of $maxRetries: " + result.nodeNotes.joinToString("; ")
        }

        val updatedEntity = current.copy(
            status = updatedStatus,
            retryCount = newRetryCount,
            errorMessage = noteText,
        )
        messageDao.updateMessage(updatedEntity)

        notifyDeliveryFailureStatus(current.sender, isThrottled, updatedStatus, newRetryCount, maxRetries, result)
    }

    private suspend fun notifyDeliveryFailureStatus(
        sender: String,
        isThrottled: Boolean,
        status: String,
        retryCount: Int,
        maxRetries: Int,
        result: DeliveryResult,
    ) {
        context?.let { ctx ->
            if (isThrottled) {
                SmsForwarderService.updateNotificationStatus(
                    ctx,
                    "⏳ Waiting on Pre-Condition",
                    "SMS from $sender: Waiting for ${result.preCheckFailures.joinToString(", ")}",
                )
            } else if (status == MessageStatus.QUEUED) {
                SmsForwarderService.updateNotificationStatus(
                    ctx,
                    "🔄 Retrying Forwarding Trigger",
                    "SMS from $sender: Attempt $retryCount of $maxRetries",
                )
            } else {
                SmsForwarderService.updateNotificationStatus(
                    ctx,
                    "❌ Forwarding Trigger Failed",
                    "Failed to deliver SMS from $sender: ${result.nodeNotes.joinToString("; ")}",
                )
                delayProvider(5000L)
                SmsForwarderService.resetToDefaultStatus(ctx)
            }
        }
    }

    private fun startActiveQueuePolling() {
        synchronized(this) {
            if (isPollingActive) return
            isPollingActive = true
        }

        pollingJob?.cancel()
        pollingJob =
            scope.launch {
                try {
                    while (isPollingActive) {
                        delay(10_000L) // Poll pre-conditions every 10s while messages are queued
                        val pending = messageDao.getPendingMessages()
                        if (pending.isEmpty()) {
                            synchronized(this@ForwardingManager) { isPollingActive = false }
                            break
                        }
                        processPendingQueue()
                    }
                } catch (e: SQLException) {
                    Log.e(TAG, "Database error in active queue polling", e)
                    synchronized(this@ForwardingManager) { isPollingActive = false }
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "State error in active queue polling", e)
                    synchronized(this@ForwardingManager) { isPollingActive = false }
                } catch (e: IOException) {
                    Log.e(TAG, "IO error in active queue polling", e)
                    synchronized(this@ForwardingManager) { isPollingActive = false }
                }
            }
    }

    suspend fun retryMessage(messageId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val current =
                messageDao.getMessageById(messageId)
                    ?: return@withContext Result.failure(IllegalArgumentException("Message log not found"))

            val rawBody = current.body ?: "Retry delivery for message from ${current.sender}"

            // Reset status to QUEUED and timestamp to now so expiry timer resets
            val resetEntity =
                current.copy(
                    receivedAt = System.currentTimeMillis(),
                    body = rawBody,
                    status = MessageStatus.QUEUED,
                    errorMessage = "Retrying message delivery...",
                )
            messageDao.updateMessage(resetEntity)

            // Process queue immediately
            processPendingQueue()
            Result.success(Unit)
        }

    fun scheduleWorkManagerRetry() {
        workManager?.let { wm ->
            val constraints = Constraints.Builder().build()

            val workRequest =
                OneTimeWorkRequestBuilder<DeliveryWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        10_000L,
                        TimeUnit.MILLISECONDS,
                    ).build()

            wm.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest,
            )
        }
    }

    suspend fun sendTestMessage(): Result<Unit> =
        withContext(Dispatchers.IO) {
            val settings = settingsRepository.getSettings()
            val activeNodes = settings.activeNodes.filter { it.isEnabled && it.serverUrl.isNotBlank() }
            if (activeNodes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No active target nodes configured"))
            }

            val testSender = "ZopHop Test"
            val testBody = "Test message from ZopHop app"

            val formattedPayload =
                MessageTemplateParser.formatPayload(
                    sender = testSender,
                    body = testBody,
                    senderRegex = settings.senderRegex,
                    bodyParseRegex = settings.bodyParseRegex,
                    template = settings.customMessageTemplate,
                )

            var overallSuccess = true
            val errors = mutableListOf<String>()

            context?.let { ctx ->
                SmsForwarderService.updateNotificationStatus(
                    ctx,
                    "🚀 Executing Test Trigger",
                    "Sending test ping to target delivery nodes...",
                )
            }

            for (node in activeNodes) {
                if (preCheckManager != null) {
                    val nodeCheck = preCheckManager.verifyNodePreChecks(node)
                    if (nodeCheck is PreCheckResult.Failed) {
                        overallSuccess = false
                        errors.add("${node.name} throttled: ${nodeCheck.reason}")
                        continue
                    }
                }

                val result =
                    ntfyClient.postMessage(
                        serverUrl = node.serverUrl,
                        topic = node.topic,
                        token = node.token,
                        sender = testSender,
                        body = formattedPayload,
                        allowInsecurePrivateHttp = settings.allowInsecureHttp,
                        serverType = node.serverType,
                    )

                if (result.isFailure) {
                    overallSuccess = false
                    errors.add("${node.name}: ${result.exceptionOrNull()?.message}")
                }
            }

            val isThrottled = errors.any { it.contains("throttled") }
            val status =
                if (overallSuccess) {
                    MessageStatus.DELIVERED
                } else if (isThrottled) {
                    MessageStatus.QUEUED
                } else {
                    MessageStatus.FAILED
                }

            val errorSummary =
                if (errors.isNotEmpty()) {
                    if (isThrottled) "⏳ Waiting for Pre-Conditions: " + errors.joinToString("; ") else errors.joinToString("; ")
                } else {
                    null
                }

            val testMsg =
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    sender = testSender,
                    body = null, // Do not store body
                    receivedAt = System.currentTimeMillis(),
                    status = status,
                    errorMessage = errorSummary,
                )
            messageDao.insertMessage(testMsg)
            messageDao.purgeOldEvents(50)

            context?.let { ctx ->
                if (overallSuccess) {
                    SmsForwarderService.updateNotificationStatus(
                        ctx,
                        "✅ Test Trigger Executed",
                        "Test ping successfully delivered to target nodes",
                    )
                } else if (isThrottled) {
                    SmsForwarderService.updateNotificationStatus(
                        ctx,
                        "⏳ Waiting on Pre-Condition",
                        "Test ping waiting for pre-conditions",
                    )
                } else {
                    SmsForwarderService.updateNotificationStatus(
                        ctx,
                        "❌ Test Trigger Failed",
                        "Test ping failed: ${errorSummary ?: "Pre-check or server error"}",
                    )
                }
                delayProvider(4000L)
                SmsForwarderService.resetToDefaultStatus(ctx)
            }

            if (overallSuccess) Result.success(Unit) else Result.failure(Exception(errorSummary ?: "Test failed"))
        }

    suspend fun clearAllEvents() =
        withContext(Dispatchers.IO) {
            messageDao.clearAllEvents()
        }

    private data class DeliveryResult(
        val allDelivered: Boolean,
        val nodeNotes: List<String>,
        val preCheckFailures: List<String>,
    )

    companion object {
        private const val TAG = "ForwardingManager"
        const val WORK_NAME = "zophop_retry_worker"

        @Volatile
        private var INSTANCE: ForwardingManager? = null

        fun getInstance(context: Context): ForwardingManager =
            INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getInstance(context)
                val repo = EncryptedSettingsRepository(context)
                val wm = WorkManager.getInstance(context.applicationContext)
                val pcm = PreCheckManager(context.applicationContext)
                val instance =
                    ForwardingManager(
                        messageDao = db.messageDao(),
                        settingsRepository = repo,
                        ntfyClient = OkHttpNtfyClient(),
                        workManager = wm,
                        preCheckManager = pcm,
                        context = context.applicationContext,
                    )
                INSTANCE = instance
                instance
            }
    }
}
