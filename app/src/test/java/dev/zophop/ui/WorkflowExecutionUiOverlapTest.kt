package dev.zophop.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.ui.workflows.NodeExecutionStepper
import dev.zophop.ui.workflows.TestRunLogBottomSheet
import dev.zophop.ui.workflows.WorkflowExecutionCard
import dev.zophop.ui.workflows.WorkflowLogsBottomSheet
import dev.zophop.ui.workflows.WorkflowsListScreen
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp", application = TestApp::class)
class WorkflowExecutionUiOverlapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    private fun assertNoOverlap(
        firstName: String,
        first: DpRect,
        secondName: String,
        second: DpRect,
    ) {
        val separatedHorizontally =
            first.right.value <= second.left.value + 0.5f ||
                second.right.value <= first.left.value + 0.5f
        val separatedVertically =
            first.bottom.value <= second.top.value + 0.5f ||
                second.bottom.value <= first.top.value + 0.5f
        assertTrue(
            "Expected '$firstName' ($first) and '$secondName' ($second) not to overlap",
            separatedHorizontally || separatedVertically,
        )
    }

    private fun assertStrictlyLeftOf(
        leftName: String,
        left: DpRect,
        rightName: String,
        right: DpRect,
    ) {
        assertTrue(
            "Expected '$leftName' ($left) to end before '$rightName' ($right)",
            left.right.value <= right.left.value + 0.5f,
        )
    }

    private fun assertStrictlyAbove(
        topName: String,
        top: DpRect,
        bottomName: String,
        bottom: DpRect,
    ) {
        assertTrue(
            "Expected '$topName' ($top) to sit above '$bottomName' ($bottom)",
            top.bottom.value <= bottom.top.value + 0.5f,
        )
    }

    private fun SemanticsNodeInteraction.bounds(): DpRect = getBoundsInRoot()

    @Test
    fun nodeExecutionStepper_narrowScreenLongNamesAndStatusChips_doNotOverlap() {
        val baseTime = 1_700_000_000_000L
        val steps =
            listOf(
                NodeExecutionStep(
                    nodeId = "node-1",
                    nodeName = "Extremely Long Trigger Node Name For Narrow Screens",
                    nodeType = NodeType.SMS_TRIGGER,
                    state = NodeStepState.SUCCESS,
                    logMessage = "Matched incoming SMS from +15551234567",
                    executedAt = baseTime,
                    durationMs = 125L,
                ),
                NodeExecutionStep(
                    nodeId = "retry_node-2",
                    nodeName = "Retry Precondition Waiter With Long Title",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.EXECUTING,
                    logMessage = "Waiting 120s remaining before retry attempt",
                    executedAt = baseTime + 5_000L,
                    durationMs = 120_000L,
                ),
                NodeExecutionStep(
                    nodeId = "node-3",
                    nodeName = "Webhook Delivery Action Node With Extra Length",
                    nodeType = NodeType.NTFY_ACTION,
                    state = NodeStepState.FAILED,
                    logMessage = "HTTP 503 Service Unavailable from upstream endpoint",
                    executedAt = baseTime + 10_000L,
                    durationMs = 3_450L,
                ),
                NodeExecutionStep(
                    nodeId = "node-4",
                    nodeName = "Downstream Fallback Notification",
                    nodeType = NodeType.SEND_NOTIFICATION_ACTION,
                    state = NodeStepState.SKIPPED,
                    logMessage = "Skipped because previous node failed",
                ),
            )

        composeTestRule.setContent {
            Box(modifier = Modifier.width(300.dp)) {
                NodeExecutionStepper(
                    steps = steps,
                    onRetryFromNode = {},
                )
            }
        }

        val step1Title = composeTestRule.onNodeWithText("Extremely Long Trigger Node Name For Narrow Screens", useUnmergedTree = true).bounds()
        val step1Chip = composeTestRule.onNodeWithText("Success", useUnmergedTree = true).bounds()
        val step1Duration = composeTestRule.onNodeWithText("125 ms", useUnmergedTree = true).bounds()
        val step1Log = composeTestRule.onNodeWithText("Matched incoming SMS from +15551234567", useUnmergedTree = true).bounds()

        assertStrictlyLeftOf("step1Title", step1Title, "step1Chip", step1Chip)
        assertStrictlyAbove("step1Title", step1Title, "step1Duration", step1Duration)
        assertStrictlyAbove("step1Chip", step1Chip, "step1Duration", step1Duration)
        assertStrictlyAbove("step1Duration", step1Duration, "step1Log", step1Log)

        val step2Title = composeTestRule.onNodeWithText("Retry Precondition Waiter With Long Title", useUnmergedTree = true).bounds()
        val step2Chip = composeTestRule.onNodeWithText("Waiting (120s)", useUnmergedTree = true).bounds()
        assertStrictlyAbove("step1Log", step1Log, "step2Title", step2Title)
        assertStrictlyLeftOf("step2Title", step2Title, "step2Chip", step2Chip)

        val step3Title = composeTestRule.onNodeWithText("Webhook Delivery Action Node With Extra Length", useUnmergedTree = true).bounds()
        val step3Chip = composeTestRule.onNodeWithText("Failed", useUnmergedTree = true).bounds()
        val step3Log = composeTestRule.onNodeWithText("HTTP 503 Service Unavailable from upstream endpoint", useUnmergedTree = true).bounds()
        val step3RetryBtn = composeTestRule.onNodeWithText("Retry From This Step", useUnmergedTree = true).bounds()
        assertStrictlyAbove("step2Title", step2Title, "step3Title", step3Title)
        assertStrictlyLeftOf("step3Title", step3Title, "step3Chip", step3Chip)
        assertStrictlyAbove("step3Log", step3Log, "step3RetryBtn", step3RetryBtn)

        val step4Title = composeTestRule.onNodeWithText("Downstream Fallback Notification", useUnmergedTree = true).bounds()
        val step4Chip = composeTestRule.onNodeWithText("Skipped", useUnmergedTree = true).bounds()
        assertStrictlyAbove("step3RetryBtn", step3RetryBtn, "step4Title", step4Title)
        assertStrictlyLeftOf("step4Title", step4Title, "step4Chip", step4Chip)
    }

    @Test
    fun workflowExecutionCard_preconditionFailedWithDurationAndExpandedSteps_doesNotOverlap() {
        val baseTime = 1_700_000_000_000L
        val steps =
            listOf(
                NodeExecutionStep(
                    nodeId = "n1",
                    nodeName = "SMS Trigger Step",
                    nodeType = NodeType.SMS_TRIGGER,
                    state = NodeStepState.SUCCESS,
                    logMessage = "Trigger matched",
                    executedAt = baseTime,
                    durationMs = 50L,
                ),
                NodeExecutionStep(
                    nodeId = "n2",
                    nodeName = "HTTP Request Step",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.FAILED,
                    logMessage = "Precondition Wi-Fi check failed",
                    executedAt = baseTime + 1_000L,
                    durationMs = 12_300L,
                    retriedInExecutionId = "RETRY1234",
                ),
            )
        val log =
            WorkflowExecutionLogEntity(
                id = "EXEC-VERY-LONG-ID-1234567890",
                workflowId = "WF-VERY-LONG-ID-0987654321",
                status = "PRECONDITION_FAILED",
                timestamp = baseTime,
                logs = steps.toJsonString(),
                errorReason = "Precondition failed due to missing Wi-Fi network connection",
            )

        composeTestRule.setContent {
            Column(
                modifier =
                    Modifier
                        .width(300.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                WorkflowExecutionCard(
                    log = log,
                    isInitiallyExpanded = true,
                    onRetryWorkflowFromFailedNode = {},
                    onDeleteExecution = {},
                )
            }
        }

        val statusBadge = composeTestRule.onNodeWithText("PRECONDITION_FAILED", useUnmergedTree = true).bounds()
        val durationText = composeTestRule.onNodeWithText("12.4 s", useUnmergedTree = true).bounds()
        val deleteBtn = composeTestRule.onNodeWithContentDescription("Delete execution", useUnmergedTree = true).bounds()
        val collapseIcon = composeTestRule.onNodeWithContentDescription("Collapse", useUnmergedTree = true).bounds()
        val wfIdBadge = composeTestRule.onNodeWithText("WF: WF-VERY-LONG-ID-0987654321", useUnmergedTree = true).bounds()
        val execIdBadge = composeTestRule.onNodeWithText("Exec: EXEC-VERY-LONG-ID-1234567890", useUnmergedTree = true).bounds()
        val errorBanner = composeTestRule.onNodeWithText("Precondition failed due to missing Wi-Fi network connection", useUnmergedTree = true).bounds()
        val firstStepTitle = composeTestRule.onNodeWithText("SMS Trigger Step", useUnmergedTree = true).bounds()
        val retriedBadge =
            composeTestRule
                .onNodeWithText("Retried in RETRY1234", useUnmergedTree = true)
                .performScrollTo()
                .bounds()
        val secondStepLog =
            composeTestRule
                .onNodeWithText("Precondition Wi-Fi check failed", useUnmergedTree = true)
                .bounds()

        assertStrictlyLeftOf("statusBadge", statusBadge, "durationText", durationText)
        assertStrictlyLeftOf("durationText", durationText, "deleteBtn", deleteBtn)
        assertStrictlyLeftOf("deleteBtn", deleteBtn, "collapseIcon", collapseIcon)
        assertNoOverlap("wfIdBadge", wfIdBadge, "execIdBadge", execIdBadge)
        assertStrictlyAbove("wfIdBadge", wfIdBadge, "errorBanner", errorBanner)
        assertStrictlyAbove("errorBanner", errorBanner, "firstStepTitle", firstStepTitle)
        assertStrictlyAbove("secondStepLog", secondStepLog, "retriedBadge", retriedBadge)
    }

    @Test
    fun testRunLogBottomSheet_headerAndStepper_doNotOverlap() {
        val steps =
            listOf(
                NodeExecutionStep(
                    nodeId = "n1",
                    nodeName = "Notification Trigger Node",
                    nodeType = NodeType.NOTIFICATION_TRIGGER,
                    state = NodeStepState.EXECUTING,
                    logMessage = "Processing payload...",
                ),
            )

        composeTestRule.setContent {
            TestRunLogBottomSheet(
                workflowName = "Very Long Automated Workflow Name That Must Not Overlap Actions",
                steps = steps,
                isTesting = true,
                onCancelExecution = {},
                onDismiss = {},
            )
        }

        val headerTitle = composeTestRule.onNodeWithText("Test Execution Stepper", useUnmergedTree = true).bounds()
        val workflowTitle = composeTestRule.onNodeWithText("Very Long Automated Workflow Name That Must Not Overlap Actions", useUnmergedTree = true).bounds()
        val cancelBtn = composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).bounds()
        val closeBtn = composeTestRule.onNodeWithContentDescription("Close", useUnmergedTree = true).bounds()
        val stepTitle = composeTestRule.onNodeWithText("Notification Trigger Node", useUnmergedTree = true).bounds()

        assertStrictlyLeftOf("headerTitle", headerTitle, "cancelBtn", cancelBtn)
        assertStrictlyLeftOf("workflowTitle", workflowTitle, "cancelBtn", cancelBtn)
        assertStrictlyLeftOf("cancelBtn", cancelBtn, "closeBtn", closeBtn)
        assertStrictlyAbove("workflowTitle", workflowTitle, "stepTitle", stepTitle)
    }

    @Test
    fun workflowLogsBottomSheet_queueTabLongNameAndActions_doNotOverlap() {
        val sampleWorkflow =
            Workflow(
                id = "WF-QUEUE-1",
                name = "Extremely Long Workflow Name Inside Queue Card That Must Not Clip",
                description = "Sample",
                isEnabled = true,
                nodes = emptyList(),
                connections = emptyList(),
            )
        val queuedLog =
            WorkflowExecutionLogEntity(
                id = "LOG-Q1",
                workflowId = sampleWorkflow.id,
                status = "QUEUED",
                timestamp = System.currentTimeMillis() - 900_000L,
                logs = "[]",
                errorReason = "Waiting for Wi-Fi connection before webhook delivery",
            )

        composeTestRule.setContent {
            WorkflowLogsBottomSheet(
                workflow = sampleWorkflow,
                logsFlow = flowOf(listOf(queuedLog)),
                onClearLogs = {},
                onRetryQueuedWorkflow = {},
                onCancelQueuedWorkflowLog = {},
                initialTab = 1,
                onDismiss = {},
            )
        }

        val queuedBadge = composeTestRule.onNodeWithText("QUEUED", useUnmergedTree = true).bounds()
        val workflowName =
            composeTestRule
                .onAllNodesWithText("Extremely Long Workflow Name Inside Queue Card That Must Not Clip", useUnmergedTree = true)[1]
                .bounds()
        val ageText = composeTestRule.onNodeWithText("Queued 15m ago", useUnmergedTree = true).bounds()
        val retryBtn = composeTestRule.onNodeWithText("Retry", useUnmergedTree = true).bounds()
        val cancelBtn = composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).bounds()

        assertStrictlyLeftOf("queuedBadge", queuedBadge, "workflowName", workflowName)
        assertStrictlyLeftOf("ageText", ageText, "retryBtn", retryBtn)
        assertStrictlyLeftOf("retryBtn", retryBtn, "cancelBtn", cancelBtn)
        assertStrictlyAbove("workflowName", workflowName, "ageText", ageText)
    }

    @Test
    fun activeExecutionBanner_insideScaffoldColumn_doesNotOverlapTopBarContentOrInternalButtons() {
        val activeState =
            ActiveExecutionState(
                workflowId = "WF-ACTIVE",
                workflowName = "Extremely Long Workflow Execution Name In Active Banner",
                isExecuting = true,
                steps =
                    listOf(
                        NodeExecutionStep(
                            nodeId = "n1",
                            nodeName = "HTTP Request",
                            nodeType = NodeType.HTTP_ACTION,
                            state = NodeStepState.EXECUTING,
                            logMessage = "Sending POST request to https://example.com/webhook/very/long/path",
                        ),
                    ),
            )
        val sampleWorkflow =
            Workflow(
                id = "WF-ACTIVE",
                name = "First Listed Workflow Card",
                description = "Sample",
                isEnabled = true,
                nodes =
                    listOf(
                        WorkflowNode(
                            id = "n1",
                            name = "SMS Trigger",
                            type = NodeType.SMS_TRIGGER,
                            isEnabled = true,
                            configJson = "{}",
                        ),
                    ),
                connections = emptyList(),
            )

        composeTestRule.setContent {
            Scaffold(
                topBar = {
                    Text(
                        text = "WorkflowsHeader",
                        modifier = Modifier.padding(16.dp),
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier = Modifier.padding(innerPadding),
                ) {
                    ActiveExecutionBanner(
                        activeState = activeState,
                        onViewSheet = {},
                        onCancelExecution = {},
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f),
                    ) {
                        WorkflowsListScreen(
                            workflows = listOf(sampleWorkflow),
                            onEditWorkflow = {},
                        )
                    }
                }
            }
        }

        val topBarText = composeTestRule.onNodeWithText("WorkflowsHeader", useUnmergedTree = true).bounds()
        val bannerTitle =
            composeTestRule
                .onNodeWithText("Executing: Extremely Long Workflow Execution Name In Active Banner", useUnmergedTree = true)
                .bounds()
        val bannerLog =
            composeTestRule
                .onNode(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.Text,
                        listOf(AnnotatedString("Sending POST request to https://example.com/webhook/very/long/path")),
                    ),
                    useUnmergedTree = true,
                ).bounds()
        val cancelBtn = composeTestRule.onNodeWithText("Cancel", useUnmergedTree = true).bounds()
        val viewBtn = composeTestRule.onNodeWithText("View", useUnmergedTree = true).bounds()
        val firstWorkflowCard = composeTestRule.onNodeWithText("First Listed Workflow Card", useUnmergedTree = true).bounds()

        assertStrictlyAbove("topBarText", topBarText, "bannerTitle", bannerTitle)
        assertStrictlyLeftOf("bannerTitle", bannerTitle, "cancelBtn", cancelBtn)
        assertStrictlyLeftOf("bannerLog", bannerLog, "cancelBtn", cancelBtn)
        assertStrictlyLeftOf("cancelBtn", cancelBtn, "viewBtn", viewBtn)
        assertStrictlyAbove("bannerLog", bannerLog, "firstWorkflowCard", firstWorkflowCard)
    }
}
