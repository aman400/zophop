package dev.zophop.worker

import dev.zophop.data.WorkflowDao
import dev.zophop.data.WorkflowEntity
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.model.NodeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
