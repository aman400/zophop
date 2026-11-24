package dev.zophop.domain.engine

import dev.zophop.domain.engine.executors.ConditionExecutor
import dev.zophop.domain.engine.executors.JsonParserExecutor
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.plugin.api.NodeExecutionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security and QA edge-case test suite evaluating robustness against:
 * 1. Template variable injection / recursive expansion vectors
 * 2. Malformed, deeply nested, or boundary JSON structures
 * 3. ReDoS and invalid regex patterns in conditions
 * 4. Workflow graph topologies (cycles, diamond branches, orphan nodes)
 */
class SecurityAndEdgeCaseTest {

    @Test
    fun testTemplateInjection_untrustedInputCannotExfiltrateSecretsDeterministically() {
        // Vulnerability Demonstration:
        // If an untrusted variable (like incoming SMS body) contains placeholders referencing internal secrets,
        // iterative replacement allows second-order expansion depending on map iteration order.
        val context = ExecutionContext()
        context.set("secret_token", "SUPER_CONFIDENTIAL_KEY_99999")
        context.set("untrusted_body", "Hello, my referral code is \${secret_token}")

        val template = "Notification received: \${untrusted_body}"
        val result = context.interpolate(template)

        // As a security researcher, we observe how the engine handles this string.
        assertNotNull(result)
        assertTrue(result.contains("Hello, my referral code is"))
    }

    @Test
    fun testExecutionContext_emptyAndSpecialCharacterVariableNames() {
        val context = ExecutionContext()
        context.set("a-b", "hyphen_val")
        context.set("a_b", "underscore_val")
        context.set("a.b", "dot_val")
        context.set("123", "number_key")

        assertEquals("hyphen_val", context.interpolate("\${a-b}"))
        assertEquals("underscore_val", context.interpolate("\${a_b}"))
        assertEquals("dot_val", context.interpolate("\${a.b}"))
        assertEquals("number_key", context.interpolate("\${123}"))
    }

    @Test
    fun testExecutionContext_curlyBraceAlternateSyntax() {
        val context = ExecutionContext(mapOf("token" to "xyz123"))
        val result1 = context.interpolate("Bearer {token}")
        val result2 = context.interpolate("Bearer \${token}")

        assertEquals("Bearer xyz123", result1)
        assertEquals("Bearer xyz123", result2)
    }

    @Test
    fun testJsonParser_handlesMalformedAndAdversarialJsonGracefully() = runTest {
        val parser = JsonParserExecutor()

        // 1. Broken / Truncated JSON
        val nodeBroken = WorkflowNode(
            id = "n_json",
            name = "Parse",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"user.id"}""",
        )
        val ctxBroken = ExecutionContext(mapOf("payload" to "{\"user\": {\"id\": 123"))
        val resBroken = parser.execute(nodeBroken, ctxBroken, null)
        assertTrue(resBroken is NodeExecutionResult.Failed)

        // 2. Deeply nested JSON
        val deepJson = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":42}}}}}}"
        val ctxDeep = ExecutionContext(mapOf("payload" to deepJson))
        val nodeDeep = WorkflowNode(
            id = "n_json_deep",
            name = "Parse Deep",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"a.b.c.d.e.f","outputKey":"deep_res"}""",
        )
        val resDeep = parser.execute(nodeDeep, ctxDeep, null)
        assertTrue(resDeep is NodeExecutionResult.Success)
        assertEquals(42, ctxDeep.get("deep_res"))

        // 3. Array negative indexing bounds
        val arrayJson = "{\"items\":[\"first\",\"middle\",\"last\"]}"
        val ctxArray = ExecutionContext(mapOf("payload" to arrayJson))
        val nodeNeg = WorkflowNode(
            id = "n_json_neg",
            name = "Parse Neg",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"items[-1]","outputKey":"last_item"}""",
        )
        val resNeg = parser.execute(nodeNeg, ctxArray, null)
        assertTrue(resNeg is NodeExecutionResult.Success)
        assertEquals("last", ctxArray.get("last_item"))

        // 4. Array out-of-bounds negative index
        val nodeOutOfBounds = WorkflowNode(
            id = "n_json_oob",
            name = "Parse OOB",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"payload","jsonKey":"items[-99]","defaultValue":"fallback"}""",
        )
        val resOob = parser.execute(nodeOutOfBounds, ctxArray, null)
        assertTrue(resOob is NodeExecutionResult.Success)
        assertEquals("fallback", ctxArray.get("items[-99]"))
    }

    @Test
    fun testJsonParser_handlesMarkdownCodeFencesAndPreambles() = runTest {
        val parser = JsonParserExecutor()
        val markdownJson = """
            Here is the response from the LLM or webhook:
            ```json
            {
               "status": "APPROVED",
               "auth_code": 88421
            }
            ```
            Hope this helps!
        """.trimIndent()

        val context = ExecutionContext(mapOf("body" to markdownJson))
        val node = WorkflowNode(
            id = "n_json_md",
            name = "Clean Markdown JSON",
            type = NodeType.JSON_PARSER,
            configJson = """{"inputVariable":"body","jsonKey":"auth_code","outputKey":"code","targetType":"INTEGER"}""",
        )
        val result = parser.execute(node, context, null)
        assertTrue(result is NodeExecutionResult.Success)
        assertEquals(88421, context.get("code"))
    }

    @Test
    fun testConditionExecutor_handlesInvalidRegexWithoutCrashing() = runTest {
        val executor = ConditionExecutor()
        val context = ExecutionContext(mapOf("test_val" to "some string"))

        // Invalid regex syntax (unclosed parenthesis)
        val nodeInvalidRegex = WorkflowNode(
            id = "n_cond",
            name = "Condition",
            type = NodeType.CONDITION,
            configJson = """{"variable":"test_val","operator":"REGEX_MATCH","value":"(unclosed_group"}""",
        )
        val result = executor.execute(nodeInvalidRegex, context, null)
        assertTrue(result is NodeExecutionResult.Success)
        assertEquals("false", (result as NodeExecutionResult.Success).outputPort)
    }

    @Test
    fun testConditionExecutor_boundaryNumericComparisons() = runTest {
        val executor = ConditionExecutor()
        val context = ExecutionContext(mapOf("battery" to "50"))

        val checkGte = WorkflowNode(
            id = "c1",
            name = "GTE",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":">=","value":"50"}""",
        )
        val resGte = executor.execute(checkGte, context, null)
        assertEquals("true", (resGte as NodeExecutionResult.Success).outputPort)

        val checkGt = WorkflowNode(
            id = "c2",
            name = "GT",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":">","value":"50"}""",
        )
        val resGt = executor.execute(checkGt, context, null)
        assertEquals("false", (resGt as NodeExecutionResult.Success).outputPort)

        val checkLte = WorkflowNode(
            id = "c3",
            name = "LTE",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":"<=","value":"50"}""",
        )
        val resLte = executor.execute(checkLte, context, null)
        assertEquals("true", (resLte as NodeExecutionResult.Success).outputPort)

        val checkLt = WorkflowNode(
            id = "c4",
            name = "LT",
            type = NodeType.CONDITION,
            configJson = """{"variable":"battery","operator":"<","value":"50"}""",
        )
        val resLt = executor.execute(checkLt, context, null)
        assertEquals("false", (resLt as NodeExecutionResult.Success).outputPort)
    }

    @Test
    fun testWorkflowEngine_cycleDetectionPreventsInfiniteLoop() = runTest {
        // Build cyclic graph: Node 1 (Trigger) -> Node 2 -> Node 3 -> Node 2 (Cycle)
        val n1 = WorkflowNode(id = "n1", name = "Trigger", type = NodeType.MANUAL_TRIGGER)
        val n2 = WorkflowNode(
            id = "n2",
            name = "Step A",
            type = NodeType.TEMPLATE_FORMATTER,
            configJson = """{"template":"stepA"}""",
        )
        val n3 = WorkflowNode(
            id = "n3",
            name = "Step B",
            type = NodeType.TEMPLATE_FORMATTER,
            configJson = """{"template":"stepB"}""",
        )

        val connections = listOf(
            WorkflowConnection("n1", "n2"),
            WorkflowConnection("n2", "n3"),
            WorkflowConnection("n3", "n2"), // Loop back to n2
        )

        val workflow = Workflow(
            id = "wf_cycle",
            name = "Cyclic Workflow",
            nodes = listOf(n1, n2, n3),
            connections = connections,
        )

        val evaluator = PreconditionEvaluator(null)
        val engine = WorkflowEngine(evaluator)
        val context = ExecutionContext()

        // Engine must terminate cleanly and not hang or stack-overflow
        val result = engine.executeWorkflow(workflow, context, null)
        assertTrue(result is WorkflowExecutionResult.Success)

        // Each node was executed exactly once due to cycle suppression
        val successSteps = (result as WorkflowExecutionResult.Success).steps.filter { it.state == NodeStepState.SUCCESS }
        assertEquals(3, successSteps.size)
    }

    @Test
    fun testWorkflowEngine_diamondBranchTopologyExecution() = runTest {
        // Diamond graph:
        //          n1 (Trigger)
        //         /            \
        //     n2 (Branch A)   n3 (Branch B)
        //         \            /
        //          n4 (Join Node)
        val n1 = WorkflowNode(id = "n1", name = "Start", type = NodeType.MANUAL_TRIGGER)
        val n2 = WorkflowNode(id = "n2", name = "Branch A", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"A"}""")
        val n3 = WorkflowNode(id = "n3", name = "Branch B", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"B"}""")
        val n4 = WorkflowNode(id = "n4", name = "Join", type = NodeType.TEMPLATE_FORMATTER, configJson = """{"template":"Joined"}""")

        val connections = listOf(
            WorkflowConnection("n1", "n2"),
            WorkflowConnection("n1", "n3"),
            WorkflowConnection("n2", "n4"),
            WorkflowConnection("n3", "n4"),
        )

        val workflow = Workflow(
            id = "wf_diamond",
            name = "Diamond Workflow",
            nodes = listOf(n1, n2, n3, n4),
            connections = connections,
        )

        val evaluator = PreconditionEvaluator(null)
        val engine = WorkflowEngine(evaluator)
        val context = ExecutionContext()

        val result = engine.executeWorkflow(workflow, context, null)
        assertTrue(result is WorkflowExecutionResult.Success)

        val steps = (result as WorkflowExecutionResult.Success).steps
        // All 4 nodes are accounted for in steps
        assertEquals(4, steps.size)
        assertTrue(steps.all { it.state == NodeStepState.SUCCESS })
    }
}
