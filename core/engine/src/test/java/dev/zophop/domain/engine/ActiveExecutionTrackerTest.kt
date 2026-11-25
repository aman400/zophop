package dev.zophop.domain.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActiveExecutionTrackerTest {

    @Before
    @After
    fun cleanup() {
        ActiveExecutionTracker.clearAll()
    }

    @Test
    fun testUpdateExecutionAndGet() {
        val steps = listOf(
            NodeExecutionStep(
                nodeId = "node_1",
                nodeName = "Step 1",
                nodeType = "ACTION",
                state = NodeStepState.EXECUTING,
            ),
        )

        ActiveExecutionTracker.updateExecution(
            workflowId = "wf_1",
            workflowName = "My Workflow",
            steps = steps,
            isExecuting = true,
        )

        val execution = ActiveExecutionTracker.getExecution("wf_1")
        assertNotNull(execution)
        assertEquals("wf_1", execution?.workflowId)
        assertEquals("My Workflow", execution?.workflowName)
        assertTrue(execution?.isExecuting == true)
        assertEquals(1, execution?.steps?.size)

        val map = ActiveExecutionTracker.executions.value
        assertEquals(1, map.size)
        assertTrue(map.containsKey("wf_1"))
    }

    @Test
    fun testRemoveExecution() {
        ActiveExecutionTracker.updateExecution(
            workflowId = "wf_1",
            workflowName = "My Workflow",
            steps = emptyList(),
            isExecuting = true,
        )
        assertNotNull(ActiveExecutionTracker.getExecution("wf_1"))

        ActiveExecutionTracker.removeExecution("wf_1")
        assertNull(ActiveExecutionTracker.getExecution("wf_1"))
        assertTrue(ActiveExecutionTracker.executions.value.isEmpty())
    }

    @Test
    fun testExecutionCompletionWithResult() {
        val result = WorkflowExecutionResult.Success(
            workflowId = "wf_done",
            logs = listOf("All good"),
            steps = emptyList(),
        )

        ActiveExecutionTracker.updateExecution(
            workflowId = "wf_done",
            workflowName = "Done Workflow",
            steps = emptyList(),
            isExecuting = false,
            result = result,
        )

        val execution = ActiveExecutionTracker.getExecution("wf_done")
        assertNotNull(execution)
        assertFalse(execution!!.isExecuting)
        assertEquals(result, execution.result)
    }
}
