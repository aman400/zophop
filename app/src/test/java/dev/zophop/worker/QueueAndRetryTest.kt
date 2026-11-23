package dev.zophop.worker

import dev.zophop.data.AppSettings
import dev.zophop.data.DeliveryNode
import dev.zophop.data.MessageDao
import dev.zophop.data.MessageEntity
import dev.zophop.data.MessageStatus
import dev.zophop.data.SettingsRepository
import dev.zophop.network.NtfyClient
import dev.zophop.network.PreCheckManager
import dev.zophop.network.PreCheckResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class FakeMessageDao : MessageDao {
    val messages = mutableMapOf<String, MessageEntity>()

    override suspend fun insertMessage(message: MessageEntity) {
        messages[message.id] = message
    }

    override suspend fun updateMessage(message: MessageEntity) {
        messages[message.id] = message
    }

    override suspend fun getMessageById(id: String): MessageEntity? = messages[id]

    override suspend fun getPendingMessages(): List<MessageEntity> =
        messages.values.filter {
            it.status == MessageStatus.QUEUED || it.status == MessageStatus.FAILED
        }

    override fun getQueuedMessagesFlow(): Flow<List<MessageEntity>> =
        flowOf(messages.values.filter { it.status == MessageStatus.QUEUED }.sortedBy { it.receivedAt })

    override fun getRecentEventsFlow(limit: Int): Flow<List<MessageEntity>> =
        flowOf(
            messages.values
                .sortedByDescending {
                    it.receivedAt
                }.take(limit),
        )

    override fun getAllEventsFlow(): Flow<List<MessageEntity>> = flowOf(messages.values.sortedByDescending { it.receivedAt })

    override suspend fun deleteMessage(id: String) {
        messages.remove(id)
    }

    override suspend fun purgeOldEvents(keepCount: Int) {
        if (messages.size > keepCount) {
            val toKeep =
                messages.values
                    .sortedByDescending { it.receivedAt }
                    .take(keepCount)
                    .map { it.id }
                    .toSet()
            val toRemove = messages.keys.filter { it !in toKeep }
            toRemove.forEach { messages.remove(it) }
        }
    }

    override suspend fun deleteMessagesOlderThan(cutoffTimestamp: Long): Int {
        val toRemove = messages.values.filter { it.receivedAt < cutoffTimestamp }.map { it.id }
        toRemove.forEach { messages.remove(it) }
        return toRemove.size
    }

    override suspend fun clearAllEvents() {
        messages.clear()
    }
}

class FakeNtfyClient(
    private val failCount: Int = 0,
) : NtfyClient {
    var callCount = 0

    override suspend fun postMessage(
        serverUrl: String,
        topic: String,
        token: String,
        sender: String,
        body: String,
        allowInsecurePrivateHttp: Boolean,
        serverType: String,
    ): Result<Unit> {
        callCount++
        return if (callCount <= failCount) {
            Result.failure(IOException("Fake connection error"))
        } else {
            Result.success(Unit)
        }
    }
}

class FakeSettingsRepository(
    private val settings: AppSettings,
) : SettingsRepository {
    constructor() : this(
        AppSettings(
            isEnabled = true,
            nodes =
                listOf(
                    DeliveryNode(
                        id = "test-node-1",
                        name = "Test Node",
                        serverType = "NTFY",
                        serverUrl = "https://ntfy.sh",
                        topic = "otp",
                        isEnabled = true,
                    ),
                ),
            expiryMinutes = 10,
        ),
    )

    override fun getSettings(): AppSettings = settings

    override fun saveSettings(settings: AppSettings) {}

    override fun setEnabled(enabled: Boolean) {}
}

class FakePreCheckManager(
    var shouldPass: Boolean = false,
    var failReason: String = "VPN connection missing",
) : PreCheckManager(context = null) {
    override suspend fun verifyNodePreChecks(node: DeliveryNode): PreCheckResult =
        if (shouldPass) PreCheckResult.Passed else PreCheckResult.Failed(failReason)
}

class QueueAndRetryTest {
    private lateinit var messageDao: FakeMessageDao
    private lateinit var settingsRepo: FakeSettingsRepository

    @Before
    fun setup() {
        messageDao = FakeMessageDao()
        settingsRepo = FakeSettingsRepository()
    }

    @Test
    fun testSuccessfulDeliveryClearsBodyAndMarksDelivered() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    delayProvider = {}, // Instant delay for fast tests
                )

            val entity = manager.enqueueIncomingSms(sender = "HDFCBK", body = "Your OTP is 123456")

            val updated = messageDao.getMessageById(entity.id)
            assertTrue(updated != null)
            assertEquals(MessageStatus.DELIVERED, updated?.status)
            assertNull(updated?.body) // OTP text must be deleted immediately on delivery!
            assertEquals(1, fakeClient.callCount)
        }

    @Test
    fun testExpiredMessageIsNotSentAndBodyIsCleared() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    delayProvider = {},
                )

            val oldTimestamp = System.currentTimeMillis() - (15 * 60 * 1000L) // 15 minutes ago
            val expiredEntity =
                MessageEntity(
                    id = "msg-old",
                    sender = "ICICIB",
                    body = "Your OTP is 999888",
                    receivedAt = oldTimestamp,
                    status = MessageStatus.QUEUED,
                )
            messageDao.insertMessage(expiredEntity)

            manager.processPendingQueue()

            val result = messageDao.getMessageById("msg-old")
            assertEquals(MessageStatus.EXPIRED, result?.status)
            assertNull(result?.body) // Body cleared on expiry
            assertEquals(0, fakeClient.callCount) // Never sent to HTTP server
        }

    @Test
    fun testImmediateRetryLoopFailsThenSucceeds() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 2) // Fails first 2 attempts, succeeds on 3rd
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    delayProvider = {},
                )

            val entity = manager.enqueueIncomingSms(sender = "HDFCBK", body = "Your OTP is 777666")

            val updated = messageDao.getMessageById(entity.id)
            assertEquals(MessageStatus.DELIVERED, updated?.status)
            assertNull(updated?.body)
            assertEquals(3, fakeClient.callCount)
        }

    @Test
    fun testManualRetryFailedMessage() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    delayProvider = {},
                )

            val failedMsg =
                MessageEntity(
                    id = "msg-fail",
                    sender = "AXISBK",
                    body = null,
                    receivedAt = System.currentTimeMillis(),
                    status = MessageStatus.FAILED,
                    errorMessage = "HTTP 500",
                )
            messageDao.insertMessage(failedMsg)

            val result = manager.retryMessage("msg-fail")
            assertTrue(result.isSuccess)
            val updated = messageDao.getMessageById("msg-fail")
            assertEquals(MessageStatus.DELIVERED, updated?.status)
        }

    @Test
    fun testPreCheckWaitingThrottlesMessageInQueuedState() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val fakePreCheck = FakePreCheckManager(shouldPass = false, failReason = "Tailscale VPN required")
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    preCheckManager = fakePreCheck,
                    delayProvider = {},
                )

            val entity = manager.enqueueIncomingSms(sender = "HDFCBK", body = "Your OTP is 123456")

            val updated = messageDao.getMessageById(entity.id)
            assertTrue(updated != null)
            assertEquals(MessageStatus.QUEUED, updated?.status)
            assertEquals(0, updated?.retryCount) // Retry count NOT incremented during throttling
            assertTrue(updated?.errorMessage?.contains("⏳ Waiting for Pre-Conditions") == true)
            assertTrue(updated?.errorMessage?.contains("Tailscale VPN required") == true)
            assertEquals(0, fakeClient.callCount) // HTTP client not called while throttled
        }

    @Test
    fun testAutoExecuteWhenPreConditionIsMet() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val fakePreCheck = FakePreCheckManager(shouldPass = false, failReason = "Tailscale VPN required")
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    preCheckManager = fakePreCheck,
                    delayProvider = {},
                )

            // Enqueue while VPN is disconnected
            val entity = manager.enqueueIncomingSms(sender = "HDFCBK", body = "Your OTP is 123456")
            assertEquals(MessageStatus.QUEUED, messageDao.getMessageById(entity.id)?.status)
            assertEquals(0, fakeClient.callCount)

            // VPN connects! Pre-checks now pass
            fakePreCheck.shouldPass = true
            manager.processPendingQueue()

            val updated = messageDao.getMessageById(entity.id)
            assertEquals(MessageStatus.DELIVERED, updated?.status)
            assertNull(updated?.body) // OTP text deleted immediately on delivery
            assertEquals(1, fakeClient.callCount) // Automatically delivered!
        }

    @Test
    fun testManualRetryResetsExpiryAndDelivers() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val fakePreCheck = FakePreCheckManager(shouldPass = true)
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    preCheckManager = fakePreCheck,
                    delayProvider = {},
                )

            val oldTime = System.currentTimeMillis() - (20 * 60 * 1000L)
            val expiredMsg =
                MessageEntity(
                    id = "msg-exp",
                    sender = "AXISBK",
                    body = "OTP 555444",
                    receivedAt = oldTime,
                    status = MessageStatus.EXPIRED,
                    errorMessage = "Expired after 10 minutes",
                )
            messageDao.insertMessage(expiredMsg)

            val result = manager.retryMessage("msg-exp")
            assertTrue(result.isSuccess)

            val updated = messageDao.getMessageById("msg-exp")
            assertEquals(MessageStatus.DELIVERED, updated?.status)
            assertNull(updated?.body)
            assertEquals(1, fakeClient.callCount)
        }

    @Test
    fun testClearAllEvents() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    delayProvider = {},
                )

            manager.enqueueIncomingSms(sender = "SENDER-1", body = "Body 1")
            manager.enqueueIncomingSms(sender = "SENDER-2", body = "Body 2")
            assertTrue(messageDao.messages.isNotEmpty())

            manager.clearAllEvents()
            assertTrue(messageDao.messages.isEmpty())
        }

    @Test
    fun testQueuedMessagesFlowEmitsOnlyQueuedMessagesSortedByReceivedAt() =
        runTest {
            val now = System.currentTimeMillis()
            val msg1 = MessageEntity("id1", "BankA", "Body 1", now + 1000, status = MessageStatus.QUEUED)
            val msg2 = MessageEntity("id2", "BankB", "Body 2", now, status = MessageStatus.QUEUED)
            val msg3 = MessageEntity("id3", "BankC", "Body 3", now + 500, status = MessageStatus.DELIVERED)
            val msg4 = MessageEntity("id4", "BankD", "Body 4", now + 200, status = MessageStatus.FAILED)

            messageDao.insertMessage(msg1)
            messageDao.insertMessage(msg2)
            messageDao.insertMessage(msg3)
            messageDao.insertMessage(msg4)

            var emittedList = emptyList<MessageEntity>()
            messageDao.getQueuedMessagesFlow().collect {
                emittedList = it
            }

            assertEquals(2, emittedList.size)
            assertEquals("id2", emittedList[0].id) // Earlier receivedAt first
            assertEquals("id1", emittedList[1].id)
        }

    @Test
    fun testQueueModeTimeoutExpirationClearsBodyAndMarksExpired() =
        runTest {
            val fakeClient = FakeNtfyClient(failCount = 0)
            val manager =
                ForwardingManager(
                    messageDao = messageDao,
                    settingsRepository = settingsRepo,
                    ntfyClient = fakeClient,
                    delayProvider = {},
                )

            val twentyMinAgo = System.currentTimeMillis() - (20 * 60 * 1000L)
            val queuedTimeoutMsg =
                MessageEntity(
                    id = "msg-queue-timeout",
                    sender = "TEST_BANK",
                    body = "Sensitive Secret 123",
                    receivedAt = twentyMinAgo,
                    status = MessageStatus.QUEUED,
                    errorMessage = "⏳ Waiting for Pre-Conditions: GPS required",
                )
            messageDao.insertMessage(queuedTimeoutMsg)

            manager.processPendingQueue()

            val updated = messageDao.getMessageById("msg-queue-timeout")
            assertEquals(MessageStatus.EXPIRED, updated?.status)
            assertNull(updated?.body)
            assertEquals(0, fakeClient.callCount)
        }
}
