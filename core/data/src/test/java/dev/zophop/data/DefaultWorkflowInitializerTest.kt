package dev.zophop.data

import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultWorkflowInitializerTest {
    class FakeWorkflowRepository : WorkflowRepository {
        val workflows = mutableListOf<Workflow>()

        override suspend fun getWorkflowCount(): Int = workflows.size

        override fun getAllWorkflowsFlow(): Flow<List<Workflow>> = flowOf(workflows)

        override suspend fun getActiveWorkflows(): List<Workflow> = workflows.filter { it.isEnabled }

        override suspend fun getWorkflowById(id: String): Workflow? = workflows.find { it.id == id }

        override suspend fun saveWorkflow(workflow: Workflow) {
            workflows.removeAll { it.id == workflow.id }
            workflows.add(workflow)
        }

        override suspend fun setWorkflowEnabled(
            id: String,
            isEnabled: Boolean,
        ) {
            val idx = workflows.indexOfFirst { it.id == id }
            if (idx != -1) {
                workflows[idx] = workflows[idx].copy(isEnabled = isEnabled)
            }
        }

        override suspend fun deleteWorkflow(id: String) {
            workflows.removeAll { it.id == id }
        }

        override fun getRecentExecutionLogsFlow(limit: Int): Flow<List<WorkflowExecutionLogEntity>> = flowOf(emptyList())

        override fun getLogsForWorkflowFlow(
            workflowId: String,
            limit: Int,
        ): Flow<List<WorkflowExecutionLogEntity>> = flowOf(emptyList())

        override suspend fun saveExecutionLog(log: WorkflowExecutionLogEntity) {}

        override suspend fun getExecutionLogById(id: String): WorkflowExecutionLogEntity? = null

        override suspend fun clearLogsForWorkflow(workflowId: String) {}
    }

    @Test
    fun testInitializerCreatesDefaultWorkflowWhenDbIsEmpty() =
        runTest {
            val repo = FakeWorkflowRepository()
            val initializer = DefaultWorkflowInitializer(repo)

            assertEquals(0, repo.getWorkflowCount())

            initializer.checkAndInitialize()

            assertEquals(1, repo.getWorkflowCount())
            val defaultWf = repo.workflows.first()
            assertEquals("default_manual_workflow", defaultWf.id)
            assertEquals(3, defaultWf.nodes.size)
            assertEquals(2, defaultWf.connections.size)
        }

    @Test
    fun testInitializerIdempotentWhenDbIsNotEmpty() =
        runTest {
            val repo = FakeWorkflowRepository()
            repo.saveWorkflow(Workflow(id = "existing_1", name = "Existing Workflow"))

            val initializer = DefaultWorkflowInitializer(repo)
            assertEquals(1, repo.getWorkflowCount())

            initializer.checkAndInitialize()

            assertEquals(1, repo.getWorkflowCount())
            assertEquals("existing_1", repo.workflows.first().id)
        }
}
