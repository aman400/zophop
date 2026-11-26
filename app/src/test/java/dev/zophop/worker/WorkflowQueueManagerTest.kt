package dev.zophop.worker

import dev.zophop.data.WorkflowDao
import dev.zophop.data.WorkflowEntity
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.NodeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class WorkflowQueueManagerTest {

    private class TestWorkflowDao : WorkflowDao {
        val logs = mutableListOf<WorkflowExecutionLogEntity>()
        val workflows = mutableListOf<WorkflowEntity>()

        override fun getAllWorkflowsFlow(): Flow<List<WorkflowEntity>> = flowOf(workflows)

        override suspend fun getActiveWorkflows(): List<WorkflowEntity> = workflows.filter { it.isEnabled }

        override suspend fun getWorkflowById(id: String): WorkflowEntity? = workflows.find { it.id == id }

        override suspend fun insertOrUpdateWorkflow(workflow: WorkflowEntity) {
            workflows.removeAll { it.id == workflow.id }
            workflows.add(workflow)
        }

        override suspend fun deleteWorkflowById(id: String) {
            workflows.removeAll { it.id == id }
        }

        override suspend fun getWorkflowCount(): Int = workflows.size

        override suspend fun insertExecutionLog(log: WorkflowExecutionLogEntity) {
            logs.removeAll { it.id == log.id }
            logs.add(log)
        }

        override suspend fun getExecutionLogById(id: String): WorkflowExecutionLogEntity? = logs.find { it.id == id }

        override fun getRecentExecutionLogsFlow(limit: Int): Flow<List<WorkflowExecutionLogEntity>> = flowOf(logs.takeLast(limit))

        override fun getLogsForWorkflowFlow(workflowId: String, limit: Int): Flow<List<WorkflowExecutionLogEntity>> =
            flowOf(logs.filter { it.workflowId == workflowId }.takeLast(limit))

        override fun getQueuedWorkflowLogsFlow(): Flow<List<WorkflowExecutionLogEntity>> =
            flowOf(logs.filter { it.status == "QUEUED" })

        override suspend fun getQueuedWorkflowLogs(): List<WorkflowExecutionLogEntity> =
            logs.filter { it.status == "QUEUED" }

        override suspend fun getRunningWorkflowLogs(): List<WorkflowExecutionLogEntity> =
            logs.filter { it.status == "RUNNING" }

        override suspend fun deleteExecutionLogById(id: String) {
            logs.removeAll { it.id == id }
        }

        override suspend fun clearLogsForWorkflow(workflowId: String) {
            logs.removeAll { it.workflowId == workflowId }
        }

        override suspend fun clearAllExecutionLogs() {
            logs.clear()
        }
    }

    @Test
    fun testQueuedWorkflowDao_recordsAndFiltersQueuedLogsCorrectly() = runTest {
        val dao = TestWorkflowDao()

        val queuedLog = WorkflowExecutionLogEntity(
            id = "log_q1",
            workflowId = "wf_sync",
            status = "QUEUED",
            timestamp = System.currentTimeMillis(),
            messageId = null,
            logs = listOf(
                NodeExecutionStep("n1", "Trigger", NodeType.MANUAL_TRIGGER, NodeStepState.SUCCESS),
                NodeExecutionStep("n2", "HTTP Action", NodeType.HTTP_ACTION, NodeStepState.FAILED, logMessage = "Internet unavailable"),
            ).toJsonString(),
            errorReason = "Internet connection unavailable",
        )

        val successLog = WorkflowExecutionLogEntity(
            id = "log_s1",
            workflowId = "wf_other",
            status = "SUCCESS",
            timestamp = System.currentTimeMillis(),
            messageId = null,
            logs = "[]",
            errorReason = null,
        )

        dao.insertExecutionLog(queuedLog)
        dao.insertExecutionLog(successLog)

        val queuedList = dao.getQueuedWorkflowLogs()
        assertEquals(1, queuedList.size)
        assertEquals("log_q1", queuedList[0].id)
        assertEquals("Internet connection unavailable", queuedList[0].errorReason)

        val flowEmission = dao.getQueuedWorkflowLogsFlow().first()
        assertEquals(1, flowEmission.size)
        assertEquals("wf_sync", flowEmission[0].workflowId)

        dao.deleteExecutionLogById("log_q1")
        assertTrue(dao.getQueuedWorkflowLogs().isEmpty())
    }

    @Test
    fun testIntuitiveNotificationText_formatsCleanly() {
        // Test queue waiting formatting
        val queueStep = NodeExecutionStep(
            nodeId = "queue_waiting_1",
            nodeName = "⏳ Queued (Attempt #1)",
            nodeType = "CONDITION",
            state = NodeStepState.EXECUTING,
            logMessage = "Waiting for Internet (No connection). Next check in 14s (9m 30s timeout remaining)...",
        )
        val notifStatus = when {
            queueStep.nodeId.startsWith("queue_waiting") -> {
                val clean = queueStep.logMessage.lineSequence().firstOrNull()?.trim()
                if (!clean.isNullOrBlank()) "⏳ Queued: $clean" else "⏳ Queued: Waiting for preconditions..."
            }
            else -> "Running"
        }
        assertTrue(notifStatus.startsWith("⏳ Queued:"))
        assertTrue(notifStatus.contains("Waiting for Internet"))

        // Test resolved step formatting
        val resolvedStep = NodeExecutionStep(
            nodeId = "queue_resolved_internet",
            nodeName = "✅ Precondition Satisfied in Queue",
            nodeType = "CONDITION",
            state = NodeStepState.SUCCESS,
        )
        val resolvedStatus = when {
            resolvedStep.nodeId.startsWith("queue_resolved") -> "✅ Conditions met. Resuming 'Send Webhook'..."
            else -> "Other"
        }
        assertEquals("✅ Conditions met. Resuming 'Send Webhook'...", resolvedStatus)
    }

    @Test
    fun testExecutionStepLogSync_transitionsRunningToQueuedAndBackToRunningOnRecovery() = runTest {
        val dao = TestWorkflowDao()
        val repo = dev.zophop.data.RoomWorkflowRepository(dao)
        val execId = "exec_sync_1"
        val wfId = "wf_sync_1"
        dao.insertExecutionLog(
            WorkflowExecutionLogEntity(
                id = execId,
                workflowId = wfId,
                status = "RUNNING",
                timestamp = System.currentTimeMillis(),
                logs = "[]",
            ),
        )

        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val sync = ExecutionStepLogSync(execId, wfId, repo, this, throttleMs = 10_000L, ioDispatcher = testDispatcher)
        sync.onStepUpdate(
            listOf(
                NodeExecutionStep(
                    nodeId = "n1",
                    nodeName = "HTTP Action",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.EXECUTING,
                    logMessage = "Evaluating preconditions [⏳ Queued (Attempt #1): Waiting for Internet]",
                    isQueueWaiting = true,
                ),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals("QUEUED", dao.getExecutionLogById(execId)?.status)

        // When precondition resolves in queue and node continues executing, status flips back to RUNNING
        sync.onStepUpdate(
            listOf(
                NodeExecutionStep(
                    nodeId = "n1",
                    nodeName = "HTTP Action",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.EXECUTING,
                    logMessage = "Executing node...",
                    isQueueWaiting = false,
                ),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals("RUNNING", dao.getExecutionLogById(execId)?.status)
    }

    @Test
    fun testReconcileStaleRunningLogs_handlesOrphanedRunningAndQueueWaitingLogs() = runTest {
        val dao = TestWorkflowDao()
        val repo = dev.zophop.data.RoomWorkflowRepository(dao)
        val staleTimestamp = System.currentTimeMillis() - 60_000L

        val orphanedRunning =
            WorkflowExecutionLogEntity(
                id = "orphan_run",
                workflowId = "wf_orphan_1",
                status = "RUNNING",
                timestamp = staleTimestamp,
                logs = "[]",
            )
        val orphanedQueuedWait =
            WorkflowExecutionLogEntity(
                id = "orphan_q",
                workflowId = "wf_orphan_2",
                status = "RUNNING",
                timestamp = staleTimestamp,
                logs =
                    listOf(
                        NodeExecutionStep(
                            nodeId = "n1",
                            nodeName = "HTTP",
                            nodeType = NodeType.HTTP_ACTION,
                            state = NodeStepState.EXECUTING,
                            isQueueWaiting = true,
                        ),
                    ).toJsonString(),
            )
        dao.insertExecutionLog(orphanedRunning)
        dao.insertExecutionLog(orphanedQueuedWait)

        WorkflowQueueManager.reconcileStaleRunningLogs(repo, nowMs = System.currentTimeMillis(), staleThresholdMs = 2_000L)

        assertEquals("FAILED", dao.getExecutionLogById("orphan_run")?.status)
        assertEquals("Execution interrupted before completion", dao.getExecutionLogById("orphan_run")?.errorReason)
        assertEquals("QUEUED", dao.getExecutionLogById("orphan_q")?.status)
    }

    @Test
    fun testExecutionStepLogSync_finalizePersistsCancelledEvenInsideCancelledJob() = runTest {
        val dao = TestWorkflowDao()
        val repo = dev.zophop.data.RoomWorkflowRepository(dao)
        val execId = "exec_cancel_finalize"
        val wfId = "wf_cancel_finalize"
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)

        val sync = ExecutionStepLogSync(execId, wfId, repo, this, throttleMs = 10_000L, ioDispatcher = testDispatcher)
        sync.onStepUpdate(
            listOf(
                NodeExecutionStep(
                    nodeId = "n1",
                    nodeName = "HTTP",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.EXECUTING,
                    isQueueWaiting = true,
                ),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals("QUEUED", dao.getExecutionLogById(execId)?.status)

        val job =
            kotlinx.coroutines.CoroutineScope(testDispatcher).launch {
                try {
                    delay(10_000.milliseconds)
                } finally {
                    sync.finalize(
                        WorkflowExecutionLogEntity(
                            id = execId,
                            workflowId = wfId,
                            status = "CANCELLED",
                            timestamp = System.currentTimeMillis(),
                            logs = "[]",
                            errorReason = "Execution cancelled by user",
                        ),
                    )
                }
            }
        testScheduler.runCurrent()
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals("CANCELLED", dao.getExecutionLogById(execId)?.status)
        assertTrue(dao.getQueuedWorkflowLogs().isEmpty())
    }

    @Test
    fun testCancelPendingLogsForWorkflow_clearsExecutingAndPendingSteps() = runTest {
        val dao = TestWorkflowDao()
        val repo = dev.zophop.data.RoomWorkflowRepository(dao)
        val steps =
            listOf(
                NodeExecutionStep("t1", "Trigger", NodeType.SMS_TRIGGER, NodeStepState.SUCCESS),
                NodeExecutionStep(
                    "a1",
                    "HTTP Action",
                    NodeType.HTTP_ACTION,
                    NodeStepState.EXECUTING,
                    logMessage = "Waiting for Wi-Fi",
                    executedAt = System.currentTimeMillis() - 5_000L,
                    isQueueWaiting = true,
                ),
                NodeExecutionStep("a2", "Notify", NodeType.NTFY_ACTION, NodeStepState.PENDING),
            )
        dao.insertExecutionLog(
            WorkflowExecutionLogEntity(
                id = "log_pending_steps",
                workflowId = "wf_pending_steps",
                status = "QUEUED",
                timestamp = System.currentTimeMillis(),
                logs = steps.toJsonString(),
                errorReason = "Waiting for Wi-Fi",
            ),
        )

        WorkflowQueueManager.cancelPendingLogsForWorkflow(
            workflowRepository = repo,
            workflowId = "wf_pending_steps",
            reason = "Execution cancelled by user",
        )

        val cancelledLog = dao.getExecutionLogById("log_pending_steps")
        assertEquals("CANCELLED", cancelledLog?.status)
        val updatedSteps = cancelledLog?.logs?.toNodeExecutionSteps().orEmpty()
        assertEquals(3, updatedSteps.size)
        assertEquals(NodeStepState.SUCCESS, updatedSteps[0].state)
        assertEquals(NodeStepState.FAILED, updatedSteps[1].state)
        assertEquals("Cancelled", updatedSteps[1].errorReason)
        assertFalse(updatedSteps[1].isQueueWaiting)
        assertEquals(NodeStepState.SKIPPED, updatedSteps[2].state)
        assertTrue(updatedSteps.none { it.state == NodeStepState.EXECUTING || it.state == NodeStepState.PENDING })
    }

    @Test
    fun testResolveRetryStartNodeId_ignoresSyntheticQueueWaitingIds() {
        val wf =
            dev.zophop.domain.model.Workflow(
                id = "WFQ12345",
                name = "Queued Flow",
                nodes =
                    listOf(
                        dev.zophop.domain.model.WorkflowNode(id = "n_trig", name = "SMS", type = NodeType.SMS_TRIGGER),
                        dev.zophop.domain.model.WorkflowNode(id = "n_act", name = "HTTP", type = NodeType.HTTP_ACTION),
                    ),
                connections = listOf(dev.zophop.domain.model.WorkflowConnection("n_trig", "n_act")),
            )

        val steps =
            listOf(
                NodeExecutionStep("n_trig", "SMS", NodeType.SMS_TRIGGER, NodeStepState.SUCCESS),
                NodeExecutionStep("n_act", "HTTP", NodeType.HTTP_ACTION, NodeStepState.EXECUTING, isQueueWaiting = true),
                NodeExecutionStep("queue_waiting_1", "Queued", "CONDITION", NodeStepState.EXECUTING, isQueueWaiting = true),
            )

        val startNodeId = WorkflowQueueManager.resolveRetryStartNodeId(wf, steps)
        assertEquals("n_act", startNodeId)
    }

    @Test
    fun testBuildQueuedRetryContextMap_populatesBodySenderAndPackageAliases() {
        val wf =
            dev.zophop.domain.model.Workflow(
                id = "WFCTX123",
                name = "Context Flow",
                nodes =
                    listOf(
                        dev.zophop.domain.model.WorkflowNode(
                            id = "n_trig",
                            name = "SMS",
                            type = NodeType.SMS_TRIGGER,
                            configJson = """{"testBody":"OTP 9988","testSender":"HDFCBK","testPackageName":"com.bank"}""",
                        ),
                    ),
                connections = emptyList(),
            )

        val ctx = WorkflowQueueManager.buildQueuedRetryContextMap(wf, showNotif = true)
        assertEquals("OTP 9988", ctx["body"])
        assertEquals("OTP 9988", ctx["text"])
        assertEquals("OTP 9988", ctx["message"])
        assertEquals("OTP 9988", ctx["http_response"])
        assertEquals("HDFCBK", ctx["sender"])
        assertEquals("HDFCBK", ctx["header"])
        assertEquals("HDFCBK", ctx["address"])
        assertEquals("com.bank", ctx["package_name"])
        assertEquals("com.bank", ctx["package"])
        assertEquals(true, ctx["show_workflow_notifications"])
    }

    @Test
    fun testExpireTimedOutQueuedLogs_expiresStale36MinQueuedExecutionWith10MinTimeout() = runTest {
        val dao = TestWorkflowDao()
        val repo = dev.zophop.data.RoomWorkflowRepository(dao)
        val nowMs = 1_800_000_000_000L
        val thirtySixMinAgo = nowMs - (36 * 60_000L)
        val twoMinAgo = nowMs - (2 * 60_000L)

        val wf =
            dev.zophop.domain.model.Workflow(
                id = "E5KM5JI3",
                name = "SMS Trigger",
                isEnabled = true,
                nodes =
                    listOf(
                        dev.zophop.domain.model.WorkflowNode(id = "trig_1", name = "SMS Trigger", type = NodeType.SMS_TRIGGER),
                        dev.zophop.domain.model.WorkflowNode(
                            id = "act_1",
                            name = "ntfy Push Action",
                            type = NodeType.NTFY_ACTION,
                            preconditions =
                                listOf(
                                    dev.zophop.domain.model.Precondition.VpnCheck(
                                        id = "vpn_1",
                                        onFailureAction = dev.zophop.domain.model.PreconditionFailurePolicy.QUEUE,
                                        queueTimeoutMinutes = 10,
                                        retryIntervalSeconds = 10,
                                    ),
                                ),
                        ),
                    ),
                connections = listOf(dev.zophop.domain.model.WorkflowConnection("trig_1", "act_1")),
            )
        dao.insertOrUpdateWorkflow(WorkflowEntity.fromDomain(wf))

        val expiredSteps =
            listOf(
                NodeExecutionStep("trig_1", "SMS Trigger", NodeType.SMS_TRIGGER, NodeStepState.SUCCESS, executedAt = thirtySixMinAgo, durationMs = 214),
                NodeExecutionStep(
                    "act_1",
                    "ntfy Push Action",
                    NodeType.NTFY_ACTION,
                    NodeStepState.EXECUTING,
                    logMessage = "Evaluating preconditions [⏳ Queued (Attempt #3): Waiting for VPN...]",
                    executedAt = thirtySixMinAgo + 214,
                    isQueueWaiting = true,
                ),
            )
        dao.insertExecutionLog(
            WorkflowExecutionLogEntity(
                id = "TE6DUYHK",
                workflowId = "E5KM5JI3",
                status = "QUEUED",
                timestamp = thirtySixMinAgo + 90_000L,
                logs = expiredSteps.toJsonString(),
                errorReason = "Waiting for VPN",
            ),
        )

        val activeSteps =
            listOf(
                NodeExecutionStep("trig_1", "SMS Trigger", NodeType.SMS_TRIGGER, NodeStepState.SUCCESS, executedAt = twoMinAgo, durationMs = 150),
                NodeExecutionStep(
                    "act_1",
                    "ntfy Push Action",
                    NodeType.NTFY_ACTION,
                    NodeStepState.EXECUTING,
                    logMessage = "Waiting for VPN",
                    executedAt = twoMinAgo + 150,
                    isQueueWaiting = true,
                ),
            )
        dao.insertExecutionLog(
            WorkflowExecutionLogEntity(
                id = "ACTIVEQ1",
                workflowId = "E5KM5JI3",
                status = "QUEUED",
                timestamp = twoMinAgo + 1_000L,
                logs = activeSteps.toJsonString(),
                errorReason = "Waiting for VPN",
            ),
        )

        WorkflowQueueManager.reconcileStaleRunningLogs(repo, nowMs = nowMs)

        val expiredLog = dao.getExecutionLogById("TE6DUYHK")
        assertEquals("PRECONDITION_FAILED", expiredLog?.status)
        assertTrue(expiredLog?.errorReason?.contains("10m queue timeout") == true)
        val updatedExpiredSteps = expiredLog?.logs?.toNodeExecutionSteps().orEmpty()
        assertEquals(NodeStepState.FAILED, updatedExpiredSteps[1].state)
        assertFalse(updatedExpiredSteps[1].isQueueWaiting)

        val activeLog = dao.getExecutionLogById("ACTIVEQ1")
        assertEquals("QUEUED", activeLog?.status)
    }
}
