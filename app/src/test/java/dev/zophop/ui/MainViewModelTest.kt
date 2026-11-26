package dev.zophop.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import dev.zophop.data.DeliveryNode
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dev.zophop.domain.engine.ActiveExecutionTracker.clearAll()
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(application)

        viewModel = MainViewModel(application)
    }

    @After
    fun tearDown() {
        dev.zophop.domain.engine.ActiveExecutionTracker.clearAll()
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialUiState() =
        runTest {
            val state = viewModel.uiState.value
            assertNotNull(state)
            assertTrue(state.isRegexValid)
            assertTrue(state.isUrlValid)
        }

    @Test
    fun testDeliveryNodeCrudOperations() =
        runTest {
            val node1 =
                DeliveryNode(
                    id = "n_100",
                    name = "Test Webhook Target",
                    serverUrl = "https://ntfy.sh",
                    topic = "test_topic",
                    isEnabled = true,
                )

            viewModel.addNode(node1)
            var state = viewModel.uiState.value
            assertEquals(1, state.nodes.size)
            assertEquals("n_100", state.nodes[0].id)
            assertEquals(1, state.activeNodes.size)

            // Update node
            val updatedNode1 = node1.copy(name = "Updated Webhook Target", isEnabled = false)
            viewModel.updateNode(updatedNode1)
            state = viewModel.uiState.value
            assertEquals("Updated Webhook Target", state.nodes[0].name)
            assertEquals(0, state.activeNodes.size)

            // Delete node
            viewModel.deleteNode("n_100")
            state = viewModel.uiState.value
            assertTrue(state.nodes.isEmpty())
        }

    @Test
    fun testRegexFiltersValidation() =
        runTest {
            viewModel.updateKeywordRegex("(?i)OTP|verification")
            assertTrue(viewModel.uiState.value.isRegexValid)
            assertEquals("(?i)OTP|verification", viewModel.uiState.value.keywordRegex)

            // Test invalid regex
            viewModel.updateKeywordRegex("[unclosed-group")
            assertFalse(viewModel.uiState.value.isRegexValid)
        }

    @Test
    fun testSettingsTogglesAndParameters() =
        runTest {
            viewModel.toggleMasterSwitch(true)
            assertTrue(viewModel.uiState.value.isEnabled)

            viewModel.toggleAllowInsecureHttp(true)
            assertTrue(viewModel.uiState.value.allowInsecureHttp)

            viewModel.toggleForwardNotifications(true)
            assertTrue(viewModel.uiState.value.forwardNotifications)

            viewModel.toggleShowWorkflowNotifications(false)
            assertFalse(viewModel.uiState.value.showWorkflowNotifications)

            viewModel.toggleShowWorkflowNotifications(true)
            assertTrue(viewModel.uiState.value.showWorkflowNotifications)

            viewModel.toggleLockCredentialsWithBiometric(true)
            assertTrue(viewModel.uiState.value.lockCredentialsWithBiometric)

            viewModel.toggleGlobalWorkflowsEnabled(false)
            assertFalse(viewModel.uiState.value.globalWorkflowsEnabled)

            viewModel.dismissBiometricBanner()
            assertTrue(viewModel.uiState.value.dismissedBiometricBanner)

            viewModel.updateMaxParallelExecutions(5)
            assertEquals(5, viewModel.uiState.value.maxParallelExecutions)

            viewModel.updateMaxExecutionQueueSize(50)
            assertEquals(50, viewModel.uiState.value.maxExecutionQueueSize)

            viewModel.updateAutoClearMinutes("720")
            assertEquals("720", viewModel.uiState.value.autoClearMinutes)
        }

    @Test
    fun testThemeModeAndPaletteUpdatesAndPersistence() =
        runTest {
            assertEquals("SYSTEM", viewModel.uiState.value.themeMode)
            assertEquals("EMERALD_AMBER", viewModel.uiState.value.themePalette)

            viewModel.updateThemeMode("DARK")
            viewModel.updateThemePalette("OCEAN_CYAN")
            assertEquals("DARK", viewModel.uiState.value.themeMode)
            assertEquals("OCEAN_CYAN", viewModel.uiState.value.themePalette)

            // Verify persisted values reload cleanly across ViewModel instances
            val reloadedViewModel = MainViewModel(application)
            assertEquals("DARK", reloadedViewModel.uiState.value.themeMode)
            assertEquals("OCEAN_CYAN", reloadedViewModel.uiState.value.themePalette)

            // Unknown values safely normalize to defaults
            viewModel.updateThemeMode("INVALID_MODE")
            viewModel.updateThemePalette("INVALID_PALETTE")
            assertEquals("SYSTEM", viewModel.uiState.value.themeMode)
            assertEquals("EMERALD_AMBER", viewModel.uiState.value.themePalette)
        }

    @Test
    fun testWorkflowRepositoryOperations() =
        runTest {
            val workflow =
                Workflow(
                    id = "wf_unit_1",
                    name = "Test SMS Forwarder",
                    description = "Forwarder description",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode("trigger", "SMS Trigger", NodeType.SMS_TRIGGER),
                            WorkflowNode(
                                "action",
                                "ntfy Action",
                                NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://ntfy.sh","topic":"test"}""",
                            ),
                        ),
                )

            viewModel.saveWorkflow(workflow)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.setWorkflowEnabled("wf_unit_1", false)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteWorkflow("wf_unit_1")
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun testCredentialAndPreconditionManagement() =
        runTest {
            val cred =
                Credential(
                    id = "c_99",
                    name = "API Key Vault",
                    type = CredentialType.BEARER_TOKEN,
                    dataMap = mapOf("token" to "secret-api-key-value"),
                )

            viewModel.saveCredential(cred)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteCredential("c_99")
            testDispatcher.scheduler.advanceUntilIdle()

            val prec =
                PreconditionEntity(
                    id = "p_88",
                    name = "Wi-Fi Check",
                    type = "HEALTH_CHECK",
                    isEnabled = true,
                )

            viewModel.savePrecondition(prec)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deletePrecondition("p_88")
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun testClearAllEventsAndCancelWorkflow() =
        runTest {
            viewModel.clearAllEvents()
            viewModel.clearLogsForWorkflow("wf_unit_1")
            viewModel.cancelWorkflowExecution("wf_unit_1")
            viewModel.clearActiveExecutionState()
            assertNull(viewModel.activeExecutionState.value)
        }

    @Test
    fun testWorkflowAndPreconditionSaveWithCustomIcons() =
        runTest {
            val workflow =
                Workflow(
                    id = "wf_icon_test",
                    name = "Iconized Workflow",
                    icon = "🚀",
                )
            viewModel.saveWorkflow(workflow)
            testDispatcher.scheduler.advanceUntilIdle()

            val prec =
                PreconditionEntity(
                    id = "prec_icon_test",
                    name = "Iconized Precondition",
                    type = "CHAINED_CHECK",
                    icon = "🛡️",
                )
            viewModel.savePrecondition(prec)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteWorkflow("wf_icon_test")
            viewModel.deletePrecondition("prec_icon_test")
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun testRetryWorkflowFromFailedNodeUpdatesOriginalLogWithRetriedInExecutionId() =
        runTest {
            val workflow =
                Workflow(
                    id = "wf_retry_test",
                    name = "Retry Test Workflow",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "trigger_1",
                                name = "SMS Trigger",
                                type = NodeType.SMS_TRIGGER,
                            ),
                            WorkflowNode(
                                id = "node_fail",
                                name = "Formatter Node",
                                type = NodeType.TEMPLATE_FORMATTER,
                                configJson = "{\"template\":\"Hello\"}",
                            ),
                        ),
                )
            viewModel.saveWorkflow(workflow)
            testDispatcher.scheduler.advanceUntilIdle()

            val failedSteps =
                listOf(
                    NodeExecutionStep(
                        nodeId = "trigger_1",
                        nodeName = "SMS Trigger",
                        nodeType = NodeType.SMS_TRIGGER,
                        state = NodeStepState.SUCCESS,
                        executedAt = System.currentTimeMillis() - 2000,
                        durationMs = 15,
                    ),
                    NodeExecutionStep(
                        nodeId = "node_fail",
                        nodeName = "Formatter Node",
                        nodeType = NodeType.TEMPLATE_FORMATTER,
                        state = NodeStepState.FAILED,
                        errorReason = "Network error",
                        executedAt = System.currentTimeMillis() - 1000,
                        durationMs = 50,
                    ),
                )

            val originalLog =
                WorkflowExecutionLogEntity(
                    id = "original_log_123",
                    workflowId = "wf_retry_test",
                    status = "FAILED",
                    timestamp = System.currentTimeMillis() - 1000,
                    messageId = null,
                    logs = failedSteps.toJsonString(),
                    errorReason = "Network error",
                )
            viewModel.workflowRepository.saveExecutionLog(originalLog)
            testDispatcher.scheduler.advanceUntilIdle()

            // Retry from failed node
            val job =
                viewModel.retryWorkflowFromFailedNode(
                    workflow = workflow,
                    failedNodeId = "node_fail",
                    originalLogId = "original_log_123",
                    onProgressUpdate = {},
                    onResult = {},
                )
            job.join()
            testDispatcher.scheduler.advanceUntilIdle()

            val updatedOriginalLog = viewModel.workflowRepository.getExecutionLogById("original_log_123")
            assertNotNull(updatedOriginalLog)
            val updatedSteps = updatedOriginalLog?.logs?.toNodeExecutionSteps()
            assertNotNull(updatedSteps)
            val retriedStep = updatedSteps?.find { it.nodeId == "node_fail" }
            assertNotNull(retriedStep)
            assertNotNull(retriedStep?.retriedInExecutionId)
            assertTrue(retriedStep?.retriedInExecutionId?.isNotBlank() == true)
        }

    @Test
    fun testBackupBridge_biometricLockTogglesWithViewModelState() {
        assertFalse(viewModel.backupBridge.isBiometricLockEnabled())
        viewModel.toggleLockCredentialsWithBiometric(true)
        assertTrue(viewModel.backupBridge.isBiometricLockEnabled())
        viewModel.toggleLockCredentialsWithBiometric(false)
        assertFalse(viewModel.backupBridge.isBiometricLockEnabled())
    }

    @Test
    fun testBackupService_exportAndImportViaViewModel() =
        runTest {
            val wf = Workflow(id = "wf_vm_test", name = "VM Export Flow", nodes = emptyList())
            viewModel.workflowRepository.saveWorkflow(wf)
            testDispatcher.scheduler.advanceUntilIdle()

            val json =
                viewModel.exportBackupJson(
                    dev.zophop.feature.backup.ExportOptions(
                        includeWorkflows = true,
                        selectedWorkflowIds = setOf("wf_vm_test"),
                        includeCredentials = false,
                        includePreconditions = false,
                    ),
                )
            assertTrue(json.contains("VM Export Flow"))

            val parsed = viewModel.parseImportJson(json)
            assertEquals(1, parsed.workflows.size)
            assertEquals("wf_vm_test", parsed.workflows[0].id)

            val importData =
                dev.zophop.feature.backup.ParsedBackupData(
                    workflows = listOf(Workflow(id = "wf_imported_via_vm", name = "Restored Via VM", nodes = emptyList())),
                )
            val result = viewModel.executeImport(importData, dev.zophop.feature.backup.ImportConflictStrategy.OVERWRITE)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, result.importedWorkflowsCount)
            val importedWf = viewModel.workflowRepository.getWorkflowById("wf_imported_via_vm")
            assertNotNull(importedWf)
            assertEquals("Restored Via VM", importedWf?.name)
        }

    @Test
    fun testNavigateToExecutingWorkflow_withActiveExecution() =
        runTest {
            dev.zophop.domain.engine.ActiveExecutionTracker.updateExecution(
                workflowId = "wf_executing_1",
                workflowName = "Active Workflow",
                steps = emptyList(),
                isExecuting = true,
            )

            viewModel.navigateToExecutingWorkflow("wf_executing_1")
            assertEquals("wf_executing_1", viewModel.targetExecutingWorkflowId.value)
            assertEquals("wf_executing_1", viewModel.activeExecutionState.value?.workflowId)
            assertTrue(viewModel.activeExecutionState.value?.isExecuting == true)

            viewModel.clearTargetExecutingWorkflowId()
            assertNull(viewModel.targetExecutingWorkflowId.value)
        }

    @Test
    fun testNavigateToExecutingWorkflow_withoutActiveExecution_opensLogs() =
        runTest {
            val wf = Workflow(id = "wf_finished_logs", name = "Finished Flow", nodes = emptyList())
            viewModel.workflowRepository.saveWorkflow(wf)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.navigateToExecutingWorkflow("wf_finished_logs")
            testDispatcher.scheduler.advanceUntilIdle()

            var attempts = 0
            while (viewModel.uiState.value.selectedWorkflowForLogs == null && attempts < 20) {
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }

            assertNull(viewModel.targetExecutingWorkflowId.value)
            assertEquals("wf_finished_logs", viewModel.uiState.value.selectedWorkflowForLogs?.id)
        }

    @Test
    fun testActiveExecutionTracker_updatesViewModelActiveExecutionState() =
        runTest {
            dev.zophop.domain.engine.ActiveExecutionTracker.updateExecution(
                workflowId = "wf_bg_exec",
                workflowName = "Background Exec",
                steps = emptyList(),
                isExecuting = true,
            )
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("wf_bg_exec", viewModel.activeExecutionState.value?.workflowId)
            assertTrue(viewModel.activeExecutionState.value?.isExecuting == true)

            viewModel.clearActiveExecutionState()
            assertNull(viewModel.activeExecutionState.value)
            assertNull(dev.zophop.domain.engine.ActiveExecutionTracker.getExecution("wf_bg_exec"))
        }

    @Test
    fun testCancelQueuedWorkflowExecution_cancelsJobDismissesNotificationAndMarksCancelled() =
        runTest {
            val wfId = "wf_queued_cancel"
            val logId = "log_queued_cancel"
            val queuedLog =
                WorkflowExecutionLogEntity(
                    id = logId,
                    workflowId = wfId,
                    status = "QUEUED",
                    timestamp = System.currentTimeMillis(),
                    logs = "[]",
                    errorReason = "Waiting for Wi-Fi",
                )
            viewModel.workflowRepository.saveExecutionLog(queuedLog)

            val job = kotlinx.coroutines.Job()
            dev.zophop.domain.engine.ExecutionJobRegistry.registerJob(wfId, job)

            val nm =
                application.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notif =
                android.app.Notification.Builder(application, "test")
                    .setContentTitle("Queued")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .build()
            nm.notify(wfId.hashCode(), notif)

            viewModel.cancelQueuedWorkflowExecution(logId)

            var updatedLog: WorkflowExecutionLogEntity? = null
            var attempts = 0
            while (attempts < 40) {
                updatedLog = viewModel.workflowRepository.getExecutionLogById(logId)
                if (updatedLog?.status == "CANCELLED") break
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }

            assertTrue(job.isCancelled)
            assertFalse(dev.zophop.domain.engine.ExecutionJobRegistry.isJobRunning(wfId))
            assertEquals("CANCELLED", updatedLog?.status)
            assertEquals("Execution cancelled from queue by user", updatedLog?.errorReason)
            assertNull(org.robolectric.Shadows.shadowOf(nm).getNotification(wfId.hashCode()))
        }

    @Test
    fun testClearLogsForWorkflow_preservesQueuedLogsAndDeleteExecutionLogRemovesSingle() =
        runTest {
            val wfId = "wf_clear_history_only"
            val completedLog =
                WorkflowExecutionLogEntity(
                    id = "log_completed",
                    workflowId = wfId,
                    status = "SUCCESS",
                    timestamp = System.currentTimeMillis() - 10_000L,
                    logs = "[]",
                )
            val failedLog =
                WorkflowExecutionLogEntity(
                    id = "log_failed",
                    workflowId = wfId,
                    status = "FAILED",
                    timestamp = System.currentTimeMillis() - 5_000L,
                    logs = "[]",
                    errorReason = "Error",
                )
            val queuedLog =
                WorkflowExecutionLogEntity(
                    id = "log_queued_keep",
                    workflowId = wfId,
                    status = "QUEUED",
                    timestamp = System.currentTimeMillis(),
                    logs = "[]",
                    errorReason = "Waiting",
                )
            viewModel.workflowRepository.saveExecutionLog(completedLog)
            viewModel.workflowRepository.saveExecutionLog(failedLog)
            viewModel.workflowRepository.saveExecutionLog(queuedLog)

            // Delete a single log first
            viewModel.deleteExecutionLog("log_completed")
            var attempts = 0
            while (attempts < 40 && viewModel.workflowRepository.getExecutionLogById("log_completed") != null) {
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }
            assertNull(viewModel.workflowRepository.getExecutionLogById("log_completed"))
            assertNotNull(viewModel.workflowRepository.getExecutionLogById("log_failed"))
            assertNotNull(viewModel.workflowRepository.getExecutionLogById("log_queued_keep"))

            // Clear all history for workflow -> removes failedLog, preserves queuedLog
            viewModel.clearLogsForWorkflow(wfId)
            attempts = 0
            while (attempts < 40 && viewModel.workflowRepository.getExecutionLogById("log_failed") != null) {
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }
            assertNull(viewModel.workflowRepository.getExecutionLogById("log_failed"))
            assertNotNull(viewModel.workflowRepository.getExecutionLogById("log_queued_keep"))
        }

    @Test
    fun testCancelQueuedWorkflowExecution_alsoCancelsStuckRunningLog() =
        runTest {
            val wfId = "wf_stuck_running"
            val logId = "log_stuck_running"
            val runningLog =
                WorkflowExecutionLogEntity(
                    id = logId,
                    workflowId = wfId,
                    status = "RUNNING",
                    timestamp = System.currentTimeMillis(),
                    logs = "[]",
                )
            viewModel.workflowRepository.saveExecutionLog(runningLog)

            val job = kotlinx.coroutines.Job()
            dev.zophop.domain.engine.ExecutionJobRegistry.registerJob(wfId, job)

            viewModel.cancelQueuedWorkflowExecution(logId)

            var updatedLog: WorkflowExecutionLogEntity? = null
            var attempts = 0
            while (attempts < 40) {
                updatedLog = viewModel.workflowRepository.getExecutionLogById(logId)
                if (updatedLog?.status == "CANCELLED") break
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }

            assertTrue(job.isCancelled)
            assertEquals("CANCELLED", updatedLog?.status)
            assertEquals("Execution cancelled by user", updatedLog?.errorReason)
        }

    @Test
    fun testCancelQueuedWorkflowExecution_clearsExecutingAndPendingStepsInSavedLog() =
        runTest {
            val wfId = "wf_queued_steps_cancel"
            val logId = "log_queued_steps_cancel"
            val initialSteps =
                listOf(
                    NodeExecutionStep(
                        nodeId = "n1",
                        nodeName = "SMS Trigger",
                        nodeType = NodeType.SMS_TRIGGER,
                        state = NodeStepState.SUCCESS,
                    ),
                    NodeExecutionStep(
                        nodeId = "n2",
                        nodeName = "HTTP Request",
                        nodeType = NodeType.HTTP_ACTION,
                        state = NodeStepState.EXECUTING,
                        logMessage = "Evaluating preconditions [⏳ Queued]",
                        executedAt = System.currentTimeMillis() - 3_000L,
                        isQueueWaiting = true,
                    ),
                    NodeExecutionStep(
                        nodeId = "n3",
                        nodeName = "ntfy Notification",
                        nodeType = NodeType.NTFY_ACTION,
                        state = NodeStepState.PENDING,
                    ),
                )
            viewModel.workflowRepository.saveExecutionLog(
                WorkflowExecutionLogEntity(
                    id = logId,
                    workflowId = wfId,
                    status = "QUEUED",
                    timestamp = System.currentTimeMillis(),
                    logs = initialSteps.toJsonString(),
                    errorReason = "Waiting for Wi-Fi",
                ),
            )

            viewModel.cancelQueuedWorkflowExecution(logId)

            var updatedLog: WorkflowExecutionLogEntity? = null
            var attempts = 0
            while (attempts < 40) {
                updatedLog = viewModel.workflowRepository.getExecutionLogById(logId)
                if (updatedLog?.status == "CANCELLED") break
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }

            assertEquals("CANCELLED", updatedLog?.status)
            val savedSteps = updatedLog?.logs?.toNodeExecutionSteps().orEmpty()
            assertEquals(3, savedSteps.size)
            assertEquals(NodeStepState.SUCCESS, savedSteps[0].state)
            assertEquals(NodeStepState.FAILED, savedSteps[1].state)
            assertEquals("Cancelled", savedSteps[1].errorReason)
            assertFalse(savedSteps[1].isQueueWaiting)
            assertEquals(NodeStepState.SKIPPED, savedSteps[2].state)
            assertTrue(savedSteps.none { it.state == NodeStepState.EXECUTING || it.state == NodeStepState.PENDING })
        }

    @Test
    fun testRunWorkflowWithProgress_assignsEightCharUppercaseAlphanumericExecutionId() =
        runTest {
            val workflow =
                Workflow(
                    id = "WFSHORT1",
                    name = "Short Exec Id Flow",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "trigger_1",
                                name = "Manual Trigger",
                                type = NodeType.MANUAL_TRIGGER,
                            ),
                        ),
                )
            viewModel.saveWorkflow(workflow)
            testDispatcher.scheduler.advanceUntilIdle()

            val job =
                viewModel.testRunWorkflowWithProgress(
                    workflow = workflow,
                    onProgressUpdate = {},
                    onResult = {},
                )
            job.join()
            testDispatcher.scheduler.advanceUntilIdle()

            var attempts = 0
            var logs = emptyList<WorkflowExecutionLogEntity>()
            while (attempts < 40) {
                logs = viewModel.getLogsForWorkflow("WFSHORT1").first()
                if (logs.isNotEmpty() && logs.first().status != "RUNNING") break
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }

            assertTrue(logs.isNotEmpty())
            val execId = logs.first().id
            assertTrue(
                "Expected 8-char uppercase alphanumeric execution ID, got '$execId'",
                Regex("^[A-Z0-9]{8}$").matches(execId),
            )
        }

    @Test
    fun testCancelPreconditionTest_cancelsOngoingEvaluationAndMarksStepsCancelled() =
        runTest {
            val entity =
                PreconditionEntity(
                    id = "prec_cancel_test_1",
                    name = "Unreachable Retrying Precondition",
                    type = PreconditionType.INTERNET_CHECK.name,
                    isEnabled = true,
                    onFailureAction = "RETRY",
                    maxRetries = 3,
                    retryIntervalSeconds = 30,
                    retryStrategy = "FIXED",
                )
            var latestSteps: List<NodeExecutionStep> = emptyList()
            var capturedResult: PreconditionResult? = null

            viewModel.testPreconditionWithProgress(
                entity = entity,
                onStepUpdate = { steps -> latestSteps = steps },
                onResult = { result -> capturedResult = result },
            )

            var attempts = 0
            while (latestSteps.none { it.state == NodeStepState.EXECUTING } && attempts < 40) {
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }
            assertTrue(
                "Expected retry countdown step to reach EXECUTING state before cancellation",
                latestSteps.any { it.state == NodeStepState.EXECUTING },
            )

            val cancelled = viewModel.cancelPreconditionTest(entity.id)
            assertTrue(cancelled)

            attempts = 0
            while (capturedResult == null && attempts < 40) {
                Thread.sleep(50)
                testDispatcher.scheduler.advanceUntilIdle()
                attempts++
            }

            val failed = capturedResult as? PreconditionResult.Failed
            assertNotNull(failed)
            assertEquals("Precondition test cancelled by user", failed?.reason)
            assertTrue(latestSteps.none { it.state == NodeStepState.EXECUTING || it.state == NodeStepState.PENDING })
            assertTrue(latestSteps.any { it.state == NodeStepState.FAILED && it.errorReason == "Cancelled" })
        }
}
