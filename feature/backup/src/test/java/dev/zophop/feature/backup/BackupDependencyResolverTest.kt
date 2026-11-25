package dev.zophop.feature.backup

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDependencyResolverTest {

    @Test
    fun testFindRelatedPreconditionIds_emptyWorkflows_returnsEmpty() {
        val precIds = BackupDependencyResolver.findRelatedPreconditionIds(emptyList())
        assertTrue(precIds.isEmpty())
    }

    @Test
    fun testFindRelatedPreconditionIds_workflowWithoutNodes_returnsEmpty() {
        val workflow = Workflow(id = "wf_empty", name = "Empty", nodes = emptyList())
        val precIds = BackupDependencyResolver.findRelatedPreconditionIds(listOf(workflow))
        assertTrue(precIds.isEmpty())
    }

    @Test
    fun testFindRelatedPreconditionIds_filtersBlankIds() {
        val node = WorkflowNode(
            id = "n1",
            name = "Test",
            type = "HTTP_ACTION",
            preconditions = listOf(
                Precondition.InternetCheck(id = ""),
                Precondition.InternetCheck(id = "   "),
                Precondition.InternetCheck(id = "prec_valid"),
            ),
        )
        val workflow = Workflow(id = "wf_1", name = "Test", nodes = listOf(node))
        val precIds = BackupDependencyResolver.findRelatedPreconditionIds(listOf(workflow))
        assertEquals(setOf("prec_valid"), precIds)
    }

    @Test
    fun testFindRelatedPreconditionIds_deduplicatesAcrossNodesAndWorkflows() {
        val node1 = WorkflowNode(
            id = "n1",
            name = "N1",
            type = "ACTION",
            preconditions = listOf(Precondition.InternetCheck(id = "shared_prec")),
        )
        val node2 = WorkflowNode(
            id = "n2",
            name = "N2",
            type = "ACTION",
            preconditions = listOf(Precondition.InternetCheck(id = "shared_prec")),
        )
        val wf1 = Workflow(id = "wf1", name = "WF1", nodes = listOf(node1))
        val wf2 = Workflow(id = "wf2", name = "WF2", nodes = listOf(node2))

        val precIds = BackupDependencyResolver.findRelatedPreconditionIds(listOf(wf1, wf2))
        assertEquals(setOf("shared_prec"), precIds)
    }

    @Test
    fun testFindRelatedCredentialIds_emptyWorkflows_returnsEmpty() {
        val credIds = BackupDependencyResolver.findRelatedCredentialIds(emptyList())
        assertTrue(credIds.isEmpty())
    }

    @Test
    fun testFindRelatedCredentialIds_malformedJson_handlesGracefully() {
        val node = WorkflowNode(
            id = "n_bad",
            name = "Bad Json",
            type = "HTTP_ACTION",
            configJson = "{ invalid json content! not a json",
        )
        val workflow = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))
        val credIds = BackupDependencyResolver.findRelatedCredentialIds(listOf(workflow))
        assertTrue(credIds.isEmpty())
    }

    @Test
    fun testFindRelatedCredentialIds_noCredentialField_returnsEmpty() {
        val node = WorkflowNode(
            id = "n_clean",
            name = "Clean",
            type = "HTTP_ACTION",
            configJson = """{"url":"https://api.example.com","method":"GET"}""",
        )
        val workflow = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))
        val credIds = BackupDependencyResolver.findRelatedCredentialIds(listOf(workflow))
        assertTrue(credIds.isEmpty())
    }

    @Test
    fun testFindRelatedCredentialIds_blankCredentialField_filteredOut() {
        val node = WorkflowNode(
            id = "n_blank",
            name = "Blank Cred",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"   "}""",
        )
        val workflow = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))
        val credIds = BackupDependencyResolver.findRelatedCredentialIds(listOf(workflow))
        assertTrue(credIds.isEmpty())
    }

    @Test
    fun testFindRelatedCredentialIds_unrelatedPreconditionsIgnored() {
        val node = WorkflowNode(
            id = "n1",
            name = "Node",
            type = "HTTP_ACTION",
            preconditions = listOf(Precondition.InternetCheck(id = "attached_prec")),
        )
        val workflow = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))

        val attachedPrecEntity = PreconditionEntity(
            id = "attached_prec",
            name = "Attached",
            type = "INTERNET_CHECK",
            credentialId = "cred_attached",
        )
        val unattachedPrecEntity = PreconditionEntity(
            id = "unattached_prec",
            name = "Unattached",
            type = "INTERNET_CHECK",
            credentialId = "cred_unattached",
        )

        val credIds = BackupDependencyResolver.findRelatedCredentialIds(
            workflows = listOf(workflow),
            availablePreconditions = listOf(attachedPrecEntity, unattachedPrecEntity),
        )

        assertEquals(setOf("cred_attached"), credIds)
        assertTrue("cred_unattached" !in credIds)
    }

    @Test
    fun testFindRelatedCredentialIds_attachedPreconditionWithBlankCredIgnored() {
        val node = WorkflowNode(
            id = "n1",
            name = "Node",
            type = "HTTP_ACTION",
            preconditions = listOf(Precondition.InternetCheck(id = "prec_1")),
        )
        val workflow = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))

        val precEntity = PreconditionEntity(
            id = "prec_1",
            name = "No Cred Rule",
            type = "INTERNET_CHECK",
            credentialId = "",
        )

        val credIds = BackupDependencyResolver.findRelatedCredentialIds(
            workflows = listOf(workflow),
            availablePreconditions = listOf(precEntity),
        )

        assertTrue(credIds.isEmpty())
    }

    @Test
    fun testResolveWorkflowDependencies_emptySelected_returnsEmpty() {
        val node = WorkflowNode(
            id = "n1",
            name = "Node",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"c1"}""",
            preconditions = listOf(Precondition.InternetCheck(id = "p1")),
        )
        val wf = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))

        val deps = BackupDependencyResolver.resolveWorkflowDependencies(
            selectedWorkflowIds = emptySet(),
            availableWorkflows = listOf(wf),
            availablePreconditions = emptyList(),
        )

        assertTrue(deps.credentialIds.isEmpty())
        assertTrue(deps.preconditionIds.isEmpty())
    }

    @Test
    fun testResolveWorkflowDependencies_nonMatchingSelectedId_returnsEmpty() {
        val node = WorkflowNode(
            id = "n1",
            name = "Node",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"c1"}""",
        )
        val wf = Workflow(id = "wf_1", name = "WF", nodes = listOf(node))

        val deps = BackupDependencyResolver.resolveWorkflowDependencies(
            selectedWorkflowIds = setOf("non_existent_wf"),
            availableWorkflows = listOf(wf),
            availablePreconditions = emptyList(),
        )

        assertTrue(deps.credentialIds.isEmpty())
        assertTrue(deps.preconditionIds.isEmpty())
    }

    @Test
    fun testResolveWorkflowDependencies_multipleWorkflowsPartialSelection() {
        val nodeA = WorkflowNode(
            id = "n_a",
            name = "Action A",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"cred_a"}""",
            preconditions = listOf(Precondition.InternetCheck(id = "prec_a")),
        )
        val wfA = Workflow(id = "wf_a", name = "Workflow A", nodes = listOf(nodeA))

        val nodeB = WorkflowNode(
            id = "n_b",
            name = "Action B",
            type = "HTTP_ACTION",
            configJson = """{"credentialId":"cred_b"}""",
            preconditions = listOf(Precondition.InternetCheck(id = "prec_b")),
        )
        val wfB = Workflow(id = "wf_b", name = "Workflow B", nodes = listOf(nodeB))

        val precEntityA = PreconditionEntity(id = "prec_a", name = "Rule A", type = "INTERNET_CHECK", credentialId = "cred_extra_a")
        val precEntityB = PreconditionEntity(id = "prec_b", name = "Rule B", type = "INTERNET_CHECK", credentialId = "cred_extra_b")

        val depsA = BackupDependencyResolver.resolveWorkflowDependencies(
            selectedWorkflowIds = setOf("wf_a"),
            availableWorkflows = listOf(wfA, wfB),
            availablePreconditions = listOf(precEntityA, precEntityB),
        )

        assertEquals(setOf("prec_a"), depsA.preconditionIds)
        assertEquals(setOf("cred_a", "cred_extra_a"), depsA.credentialIds)
        assertTrue("cred_b" !in depsA.credentialIds)
        assertTrue("cred_extra_b" !in depsA.credentialIds)
        assertTrue("prec_b" !in depsA.preconditionIds)
    }
}
