package dev.zophop.integration

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.data.AppDatabase
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.RoomWorkflowRepository
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.domain.engine.NodeExecutorFactory
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.receiver.SmsReceiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkflowExecutionNotificationTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(context as Application).grantPermissions(
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        ExecutionJobRegistry.cancelAllJobs()
        notificationManager.cancelAll()
    }

    @Test
    fun workflowEngine_whenTriggerFilteredOut_cancelsOngoingNotificationAndReturnsIgnored() =
        runBlocking {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val engine =
                WorkflowEngine(
                    preconditionEvaluator = PreconditionEvaluator(context = context, okHttpClient = okHttpClient),
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val workflow =
                Workflow(
                    id = "wf_ignored_notif_test",
                    name = "Bank OTP Forwarder",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "t1",
                                name = "SMS Trigger",
                                type = NodeType.SMS_TRIGGER,
                                configJson = """{"allowlist":"HDFC.*","keywordRegex":".*"}""",
                            ),
                            WorkflowNode(
                                id = "a1",
                                name = "ntfy Target",
                                type = NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://ntfy.sh","topic":"alerts"}""",
                            ),
                        ),
                    connections = listOf(WorkflowConnection("t1", "a1")),
                )

            val execContext =
                ExecutionContext(
                    mapOf(
                        "sender" to "PROMO_SPAM",
                        "body" to "50% off sale today!",
                        "show_workflow_notifications" to true,
                    ),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = context)

            assertTrue("Expected Ignored result when trigger filter does not match", result is WorkflowExecutionResult.Ignored)
            assertTrue(result.steps.all { it.state == NodeStepState.SKIPPED })

            val shadowNm = Shadows.shadowOf(notificationManager)
            assertEquals(
                "Ongoing notification with Cancel button must be dismissed when trigger is ignored",
                0,
                shadowNm.allNotifications.size,
            )
        }

    @Test
    fun workflowEngine_whenPreconditionFailsAndAlertsEnabled_postsFailureNotification() =
        runBlocking {
            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(503)
                            .message("Service Unavailable")
                            .body("unavailable".toResponseBody())
                            .build()
                    }.build()

            val engine =
                WorkflowEngine(
                    preconditionEvaluator = PreconditionEvaluator(context = context, okHttpClient = okHttpClient),
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val workflow =
                Workflow(
                    id = "wf_fail_notif_test",
                    name = "Alert Failure Workflow",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "t1",
                                name = "SMS Trigger",
                                type = NodeType.SMS_TRIGGER,
                                configJson = """{"allowlist":".*","keywordRegex":".*"}""",
                            ),
                            WorkflowNode(
                                id = "a1",
                                name = "ntfy Target",
                                type = NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://ntfy.sh","topic":"alerts"}""",
                                preconditions =
                                    listOf(
                                        Precondition.HealthCheck(
                                            isEnabled = true,
                                            targetUrl = "https://ntfy.sh/health",
                                            expectedStatus = 200,
                                        ),
                                    ),
                            ),
                        ),
                    connections = listOf(WorkflowConnection("t1", "a1")),
                )

            val execContext =
                ExecutionContext(
                    mapOf(
                        "sender" to "HDFC_BANK",
                        "body" to "Your OTP is 123456",
                        "show_workflow_notifications" to true,
                    ),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = context)

            assertTrue(result is WorkflowExecutionResult.PreconditionFailed)

            val shadowNm = Shadows.shadowOf(notificationManager)
            assertEquals(
                "Failure notification must be posted when show_workflow_notifications is true",
                1,
                shadowNm.allNotifications.size,
            )
            val posted = shadowNm.allNotifications.first()
            val title = posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            assertEquals("Alert Failure Workflow", title)
        }

    @Test
    fun smsReceiver_whenTriggerFilterDoesNotMatch_discardsHistoryAndDismissesNotification() =
        runBlocking {
            val db = AppDatabase.getInstance(context)
            db.workflowDao().clearAllExecutionLogs()
            val workflowRepo = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())

            val settingsRepo = EncryptedSettingsRepository(context)
            settingsRepo.saveSettings(
                settingsRepo.getSettings().copy(
                    globalWorkflowsEnabled = true,
                    showWorkflowNotifications = true,
                ),
            )

            val workflow =
                Workflow(
                    id = "wf_sms_filtered_out",
                    name = "Strict Bank Filter",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "sms_trigger_strict",
                                name = "Strict SMS Trigger",
                                type = NodeType.SMS_TRIGGER,
                                configJson = """{"allowlist":"HDFC.*","keywordRegex":"OTP"}""",
                            ),
                            WorkflowNode(
                                id = "format_action_strict",
                                name = "Format Message",
                                type = NodeType.TEMPLATE_FORMATTER,
                                configJson = """{"template":"${'$'}{body}"}""",
                            ),
                        ),
                    connections = listOf(WorkflowConnection("sms_trigger_strict", "format_action_strict")),
                )
            workflowRepo.saveWorkflow(workflow)

            val receiver = SmsReceiver()
            val intent =
                Intent("android.provider.Telephony.SMS_RECEIVED").apply {
                    putExtra("sender", "PROMO_OFFER")
                    putExtra("body", "Win a prize today")
                }

            receiver.onReceive(context, intent)
            delay(1000.milliseconds)

            val logs = db.workflowDao().getLogsForWorkflowFlow("wf_sms_filtered_out").first()
            assertTrue("No execution history should be stored when trigger filter discards workflow", logs.isEmpty())

            val shadowNm = Shadows.shadowOf(notificationManager)
            assertEquals("Notification must be dismissed when execution is skipped", 0, shadowNm.allNotifications.size)
        }

    @Test
    fun workflowEngine_duringActiveExecutionWithAlertsEnabled_postsOngoingNotificationAndDismissesOnSuccess() =
        runBlocking {
            val shadowNm = Shadows.shadowOf(notificationManager)
            var notificationsCountDuringExecution = 0
            var notificationTitleDuringExecution: String? = null
            var hasCancelAction = false

            val okHttpClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        notificationsCountDuringExecution = shadowNm.allNotifications.size
                        val notif = shadowNm.allNotifications.firstOrNull()
                        notificationTitleDuringExecution =
                            notif?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                        hasCancelAction = notif?.actions?.any { it.title == "Cancel" } == true
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("ok".toResponseBody())
                            .build()
                    }.build()

            val engine =
                WorkflowEngine(
                    preconditionEvaluator = PreconditionEvaluator(context = context, okHttpClient = okHttpClient),
                    credentialRepository = null,
                    executorFactory = NodeExecutorFactory(okHttpClient),
                )

            val workflow =
                Workflow(
                    id = "wf_active_notif_test",
                    name = "Active Execution Alert Workflow",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "t1",
                                name = "SMS Trigger",
                                type = NodeType.SMS_TRIGGER,
                                configJson = """{"allowlist":".*","keywordRegex":".*"}""",
                            ),
                            WorkflowNode(
                                id = "a1",
                                name = "ntfy Target",
                                type = NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://ntfy.sh","topic":"alerts"}""",
                            ),
                        ),
                    connections = listOf(WorkflowConnection("t1", "a1")),
                )

            val execContext =
                ExecutionContext(
                    mapOf(
                        "sender" to "HDFC_BANK",
                        "body" to "Your OTP is 123456",
                        "show_workflow_notifications" to true,
                    ),
                )

            val result = engine.executeWorkflow(workflow, execContext, appContext = context)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals(1, notificationsCountDuringExecution)
            assertEquals("Active Execution Alert Workflow", notificationTitleDuringExecution)
            assertTrue("Ongoing notification must include Cancel action", hasCancelAction)
            assertEquals("Ongoing notification should be cleared after success", 0, shadowNm.allNotifications.size)
        }

    @Test
    fun workflowCancelReceiver_cancellingFromNotificationRemovesWorkflowFromQueueAndMarksCancelled() =
        runBlocking {
            val db = AppDatabase.getInstance(context)
            db.workflowDao().clearAllExecutionLogs()
            val workflowRepo = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())

            val queuedLog =
                dev.zophop.data.WorkflowExecutionLogEntity(
                    id = "log_notif_cancel_q",
                    workflowId = "wf_notif_cancel_q",
                    status = "QUEUED",
                    timestamp = System.currentTimeMillis(),
                    logs = "[]",
                    errorReason = "Waiting in queue",
                )
            workflowRepo.saveExecutionLog(queuedLog)

            val receiver = dev.zophop.receiver.WorkflowCancelReceiver()
            val cancelIntent =
                Intent("dev.zophop.ACTION_CANCEL_WORKFLOW").apply {
                    putExtra("workflow_id", "wf_notif_cancel_q")
                    putExtra("notification_id", "wf_notif_cancel_q".hashCode())
                }

            receiver.onReceive(context, cancelIntent)
            delay(400.milliseconds)

            val updatedLog = workflowRepo.getExecutionLogById("log_notif_cancel_q")
            assertEquals("CANCELLED", updatedLog?.status)
            assertTrue(
                "Cancelled workflow must no longer appear in queued logs",
                workflowRepo.getQueuedWorkflowLogs().none { it.workflowId == "wf_notif_cancel_q" },
            )
        }
}
