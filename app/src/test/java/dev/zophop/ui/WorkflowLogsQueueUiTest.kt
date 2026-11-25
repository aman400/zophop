package dev.zophop.ui

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.data.MessageEntity
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.ui.workflows.WorkflowLogsBottomSheet
import dev.zophop.ui.workflows.WorkflowsListScreen
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h891dp", application = TestApp::class)
class WorkflowLogsQueueUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application

    private val sampleWorkflow =
        Workflow(
            id = "wf-1",
            name = "OTP Forwarder",
            description = "Forwards OTP SMS",
            isEnabled = true,
            nodes =
                listOf(
                    WorkflowNode(
                        id = "node-1",
                        name = "SMS Trigger",
                        type = NodeType.SMS_TRIGGER,
                        isEnabled = true,
                        configJson = "{}",
                    ),
                ),
            connections = emptyList(),
        )

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun workflowsListScreen_doesNotShowPendingQueueCard_andQueuedChipOpensHistoryQueueTab() {
        val queuedLog =
            WorkflowExecutionLogEntity(
                id = "log-q1",
                workflowId = sampleWorkflow.id,
                status = "QUEUED",
                timestamp = System.currentTimeMillis() - 60_000L,
                logs = "[]",
                errorReason = "Waiting for Wi-Fi",
            )
        val queuedMessage =
            MessageEntity(
                id = "msg-q1",
                sender = "+15550001111",
                body = null,
                receivedAt = System.currentTimeMillis() - 120_000L,
                status = "QUEUED",
                retryCount = 1,
                errorMessage = "Network unavailable",
            )

        composeTestRule.setContent {
            WorkflowsListScreen(
                workflows = listOf(sampleWorkflow),
                queuedMessages = listOf(queuedMessage),
                queuedWorkflowIds = setOf(sampleWorkflow.id),
                onEditWorkflow = {},
                onGetLogsForWorkflowFlow = { flowOf(listOf(queuedLog)) },
                onProcessQueueNow = {},
            )
        }

        // PendingQueueCard must no longer exist on the Workflows list screen
        composeTestRule.onNodeWithText("Pending Queue:", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("⏳ Queued").assertExists().performClick()
        composeTestRule.waitForIdle()

        // Tapping the Queued chip opens the Execution History bottom sheet directly on the Queue tab
        composeTestRule.onNodeWithText("Execution History").assertExists()
        composeTestRule.onNodeWithText("Queue (2)").assertExists()
        composeTestRule.onNodeWithText("Waiting for Wi-Fi").assertExists()
        composeTestRule.onNodeWithText("+15550001111").assertExists()
        composeTestRule.onNodeWithText("Process Queue Now").assertExists()
    }

    @Test
    fun workflowLogsBottomSheet_displaysQueueInsideHistorySheetAndHandlesActions() {
        val successLog =
            WorkflowExecutionLogEntity(
                id = "log-s1",
                workflowId = sampleWorkflow.id,
                status = "SUCCESS",
                timestamp = System.currentTimeMillis() - 300_000L,
                logs = "[]",
                errorReason = null,
            )
        val queuedLog =
            WorkflowExecutionLogEntity(
                id = "log-q1",
                workflowId = sampleWorkflow.id,
                status = "QUEUED",
                timestamp = System.currentTimeMillis() - 60_000L,
                logs = "[]",
                errorReason = "Precondition Wi-Fi not satisfied",
            )
        val queuedMessage =
            MessageEntity(
                id = "msg-q1",
                sender = "+15551234567",
                body = null,
                receivedAt = System.currentTimeMillis() - 180_000L,
                status = "QUEUED",
                retryCount = 2,
                errorMessage = "No network",
            )

        var retriedLogId: String? = null
        var canceledLogId: String? = null
        var canceledMsgId: String? = null
        var processQueueClicked = false

        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(listOf(queuedLog, successLog)),
                onClearLogs = {},
                queuedMessages = listOf(queuedMessage),
                onRetryQueuedWorkflow = { retriedLogId = it.id },
                onCancelQueuedWorkflowLog = { canceledLogId = it },
                onCancelQueuedMessage = { canceledMsgId = it },
                onProcessQueueNow = { processQueueClicked = true },
                onDismiss = {},
            )
        }

        // Starts on History tab
        composeTestRule.onNodeWithText("Execution History").assertExists()
        composeTestRule.onNodeWithText("History (2)").assertExists()
        composeTestRule.onNodeWithText("Queue (2)").assertExists().performClick()
        composeTestRule.waitForIdle()

        // Queue tab displays queued workflow and queued message, with no bulk Clear button
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()
        composeTestRule.onNodeWithText("Precondition Wi-Fi not satisfied").assertExists()
        composeTestRule.onNodeWithText("+15551234567").assertExists()
        composeTestRule.onNodeWithText("No network").assertExists()

        // Trigger Retry on queued workflow
        composeTestRule.onNodeWithText("Retry").performClick()
        assertEquals("log-q1", retriedLogId)

        // Canceling queued workflow requires confirmation (dismissing keeps it, confirming cancels)
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Cancel Queued Execution?").assertExists()
        composeTestRule.onNodeWithText("Keep").performClick()
        assertEquals(null, canceledLogId)

        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Cancel Execution").performClick()
        composeTestRule.waitForIdle()
        assertEquals("log-q1", canceledLogId)

        // Removing queued message requires confirmation
        composeTestRule.onNodeWithContentDescription("Cancel").performClick()
        composeTestRule.onNodeWithText("Remove Queued Message?").assertExists()
        composeTestRule.onNodeWithText("Keep").performClick()
        assertEquals(null, canceledMsgId)

        composeTestRule.onNodeWithContentDescription("Cancel").performClick()
        composeTestRule.onNodeWithText("Remove").performClick()
        composeTestRule.waitForIdle()
        assertEquals("msg-q1", canceledMsgId)

        // Trigger Process Queue Now
        composeTestRule.onNodeWithText("Process Queue Now").performClick()
        assertTrue(processQueueClicked)
    }

    @Test
    fun workflowLogsBottomSheet_historyTab_allowsCancelingQueuedExecutionWithConfirmation_andHidesClearWhenNoHistory() {
        val queuedLog =
            WorkflowExecutionLogEntity(
                id = "log-q-hist",
                workflowId = sampleWorkflow.id,
                status = "QUEUED",
                timestamp = System.currentTimeMillis() - 60_000L,
                logs = "[]",
                errorReason = "Waiting for internet",
            )
        var canceledLogId: String? = null

        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(listOf(queuedLog)),
                onClearLogs = {},
                onCancelQueuedWorkflowLog = { canceledLogId = it },
                onDismiss = {},
            )
        }

        // Only pending queue item exists -> bulk Clear is not shown in History header
        composeTestRule.onNodeWithText("History (1)").assertExists()
        composeTestRule.onNodeWithText("Clear").assertDoesNotExist()

        composeTestRule.onNodeWithText("Cancel Execution").assertExists().performClick()
        composeTestRule.onNodeWithText("Cancel Queued Execution?").assertExists()
        composeTestRule.onNodeWithText("Keep").performClick()
        assertEquals(null, canceledLogId)

        composeTestRule.onNodeWithText("Cancel Execution").performClick()
        composeTestRule.onNodeWithText("Cancel Queued Execution?").assertExists()
        // Click the confirm button inside the AlertDialog (onAllNodesWithText -> second is dialog confirm button)
        composeTestRule
            .onAllNodes(androidx.compose.ui.test.hasText("Cancel Execution") and androidx.compose.ui.test.hasClickAction())
            .let { nodes -> nodes[nodes.fetchSemanticsNodes().size - 1].performClick() }
        composeTestRule.waitForIdle()
        assertEquals("log-q-hist", canceledLogId)
    }

    @Test
    fun workflowLogsBottomSheet_historyTab_singleExecutionDeleteAndClearAllRequireConfirmation() {
        val successLog =
            WorkflowExecutionLogEntity(
                id = "log-s1",
                workflowId = sampleWorkflow.id,
                status = "SUCCESS",
                timestamp = System.currentTimeMillis() - 300_000L,
                logs = "[]",
                errorReason = null,
            )
        val failedLog =
            WorkflowExecutionLogEntity(
                id = "log-f1",
                workflowId = sampleWorkflow.id,
                status = "FAILED",
                timestamp = System.currentTimeMillis() - 150_000L,
                logs = "[]",
                errorReason = "Timeout",
            )
        var deletedLogId: String? = null
        var clearedAll = false

        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(listOf(successLog, failedLog)),
                onClearLogs = { clearedAll = true },
                onDeleteExecutionLog = { deletedLogId = it },
                onDismiss = {},
            )
        }

        // Single execution delete with confirmation
        composeTestRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Delete execution"))[0].performClick()
        composeTestRule.onNodeWithText("Delete Execution Log?").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(null, deletedLogId)

        composeTestRule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Delete execution"))[1].performClick()
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()
        assertEquals("log-f1", deletedLogId)

        // Clear all history with confirmation
        composeTestRule.onNodeWithText("Clear").assertExists().performClick()
        composeTestRule.onNodeWithText("Clear Execution History?").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(false, clearedAll)

        composeTestRule.onNodeWithText("Clear").performClick()
        composeTestRule.onNodeWithText("Clear History").performClick()
        composeTestRule.waitForIdle()
        assertTrue(clearedAll)
    }

    @Test
    fun workflowLogsBottomSheet_emptyQueueTab_showsEmptyState() {
        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(emptyList()),
                onClearLogs = {},
                queuedMessages = emptyList(),
                initialTab = 1,
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Queue (0)").assertExists()
        composeTestRule.onNodeWithText("No queued executions waiting.").assertExists()
        composeTestRule.onNodeWithText("Process Queue Now").assertDoesNotExist()
    }

    @Test
    fun mainAppScreen_doesNotShowPendingQueueBanner() {
        val viewModel = MainViewModel(application)

        composeTestRule.setContent {
            MainAppScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = true,
                onRequestNotificationPermission = {},
            )
        }

        composeTestRule.onNodeWithText("Pending Queue:", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Pending Execution Queue").assertDoesNotExist()
    }

    @Test
    fun workflowLogsBottomSheet_historyTab_allowsCancelingRunningExecutionWithConfirmation() {
        val runningLog =
            WorkflowExecutionLogEntity(
                id = "log-running-1",
                workflowId = sampleWorkflow.id,
                status = "RUNNING",
                timestamp = System.currentTimeMillis(),
                logs = "[]",
            )
        var canceledLogId: String? = null

        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(listOf(runningLog)),
                onClearLogs = {},
                onCancelQueuedWorkflowLog = { canceledLogId = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("RUNNING").assertExists()
        composeTestRule.onNodeWithText("Cancel Execution").assertExists().performClick()
        composeTestRule.onNodeWithText("Cancel Running Execution?").assertExists()
        composeTestRule.onNodeWithText("Keep").performClick()
        assertEquals(null, canceledLogId)

        composeTestRule.onNodeWithText("Cancel Execution").performClick()
        composeTestRule.onNodeWithText("Cancel Running Execution?").assertExists()
        composeTestRule.onAllNodesWithText("Cancel Execution")[1].performClick()
        composeTestRule.waitForIdle()
        assertEquals("log-running-1", canceledLogId)
    }

    @Test
    fun workflowLogsBottomSheet_cancelledLogWithStaleExecutingStep_doesNotShowInProgress() {
        val staleSteps =
            listOf(
                NodeExecutionStep(
                    nodeId = "node-1",
                    nodeName = "SMS Trigger",
                    nodeType = NodeType.SMS_TRIGGER,
                    state = NodeStepState.SUCCESS,
                ),
                NodeExecutionStep(
                    nodeId = "node-2",
                    nodeName = "HTTP Request",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.EXECUTING,
                    logMessage = "Waiting for Wi-Fi",
                    isQueueWaiting = true,
                ),
                NodeExecutionStep(
                    nodeId = "node-3",
                    nodeName = "ntfy Alert",
                    nodeType = NodeType.NTFY_ACTION,
                    state = NodeStepState.PENDING,
                ),
            )
        val cancelledLog =
            WorkflowExecutionLogEntity(
                id = "log-cancelled-stale",
                workflowId = sampleWorkflow.id,
                status = "CANCELLED",
                timestamp = System.currentTimeMillis(),
                logs = staleSteps.toJsonString(),
                errorReason = "Execution cancelled from queue by user",
            )

        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(listOf(cancelledLog)),
                onClearLogs = {},
                onDismiss = {},
            )
        }

        // First card in history is initially expanded; verify cancelled step normalization
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Collapse").assertExists()

        composeTestRule.onNodeWithText("CANCELLED").assertExists()
        composeTestRule.onNodeWithText("In Progress...").assertDoesNotExist()
        composeTestRule.onNodeWithText("Failed").assertExists()
        composeTestRule.onNodeWithText("Skipped").assertExists()
    }

    @Test
    fun workflowsListScreen_createWorkflow_assignsEightCharUppercaseAlphanumericId() {
        var createdWorkflow: Workflow? = null

        composeTestRule.setContent {
            WorkflowsListScreen(
                workflows = emptyList(),
                onEditWorkflow = { createdWorkflow = it },
            )
        }

        composeTestRule.onNodeWithContentDescription("Add Workflow").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("SMS Trigger", substring = true)[0].performClick()
        composeTestRule.waitForIdle()

        val id = createdWorkflow?.id
        assertTrue(
            "Expected 8-char uppercase alphanumeric workflow ID, got '$id'",
            id != null && Regex("^[A-Z0-9]{8}$").matches(id),
        )
    }
}
