package dev.zophop.data

import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRepositoryTest {
    private class FakeWorkflowDao : WorkflowDao {
        val workflows = mutableMapOf<String, WorkflowEntity>()
        val logs = mutableListOf<WorkflowExecutionLogEntity>()

        override fun getAllWorkflowsFlow() = flowOf(workflows.values.toList())

        override suspend fun getActiveWorkflows(): List<WorkflowEntity> = workflows.values.filter { it.isEnabled }

        override suspend fun getWorkflowById(id: String): WorkflowEntity? = workflows[id]

        override suspend fun insertOrUpdateWorkflow(workflow: WorkflowEntity) {
            workflows[workflow.id] = workflow
        }

        override suspend fun deleteWorkflowById(id: String) {
            workflows.remove(id)
        }

        override suspend fun getWorkflowCount(): Int = workflows.size

        override suspend fun insertExecutionLog(log: WorkflowExecutionLogEntity) {
            logs.add(log)
        }

        override fun getRecentExecutionLogsFlow(limit: Int) = flowOf(logs.takeLast(limit))

        override fun getLogsForWorkflowFlow(
            workflowId: String,
            limit: Int,
        ) = flowOf(logs.filter { it.workflowId == workflowId }.takeLast(limit))

        override suspend fun clearLogsForWorkflow(workflowId: String) {
            logs.removeAll { it.workflowId == workflowId }
        }

        override suspend fun clearAllExecutionLogs() {
            logs.clear()
        }
    }

    @Test
    fun testWorkflowCrudOperations() =
        runTest {
            val fakeDao = FakeWorkflowDao()
            val repo = RoomWorkflowRepository(fakeDao)

            val workflow =
                Workflow(
                    id = "wf_1",
                    name = "SMS Forwarder Workflow",
                    description = "Forwards OTP SMS",
                    isEnabled = true,
                    nodes = listOf(WorkflowNode("n1", "SMS", NodeType.SMS_TRIGGER)),
                )

            repo.saveWorkflow(workflow)

            val saved = repo.getWorkflowById("wf_1")
            assertNotNull(saved)
            assertEquals("SMS Forwarder Workflow", saved?.name)
            assertTrue(saved?.isEnabled == true)

            val allWorkflows = repo.getAllWorkflowsFlow().first()
            assertEquals(1, allWorkflows.size)

            val active = repo.getActiveWorkflows()
            assertEquals(1, active.size)

            // Disable workflow
            repo.setWorkflowEnabled("wf_1", false)
            val disabled = repo.getWorkflowById("wf_1")
            assertEquals(false, disabled?.isEnabled)
            assertTrue(repo.getActiveWorkflows().isEmpty())

            // Delete workflow
            repo.deleteWorkflow("wf_1")
            assertNull(repo.getWorkflowById("wf_1"))
            assertEquals(0, repo.getWorkflowCount())
        }

    @Test
    fun testExecutionLogOperations() =
        runTest {
            val fakeDao = FakeWorkflowDao()
            val repo = RoomWorkflowRepository(fakeDao)

            val log1 =
                WorkflowExecutionLogEntity(
                    id = "log_1",
                    workflowId = "wf_1",
                    status = "SUCCESS",
                    logs = "Node 1 executed successfully",
                    timestamp = System.currentTimeMillis(),
                )
            val log2 =
                WorkflowExecutionLogEntity(
                    id = "log_2",
                    workflowId = "wf_2",
                    status = "FAILED",
                    logs = "Precondition failed",
                    errorReason = "Network offline",
                    timestamp = System.currentTimeMillis(),
                )

            repo.saveExecutionLog(log1)
            repo.saveExecutionLog(log2)

            val recentLogs = repo.getRecentExecutionLogsFlow(50).first()
            assertEquals(2, recentLogs.size)

            val wf1Logs = repo.getLogsForWorkflowFlow("wf_1", 50).first()
            assertEquals(1, wf1Logs.size)
            assertEquals("log_1", wf1Logs[0].id)

            repo.clearLogsForWorkflow("wf_1")
            val wf1LogsAfterClear = repo.getLogsForWorkflowFlow("wf_1", 50).first()
            assertTrue(wf1LogsAfterClear.isEmpty())
            assertEquals(1, repo.getRecentExecutionLogsFlow(50).first().size)
        }
}
