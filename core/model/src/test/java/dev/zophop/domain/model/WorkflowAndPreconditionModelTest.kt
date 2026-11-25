package dev.zophop.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAndPreconditionModelTest {
    @Test
    fun testWorkflowModelImmutabilityAndCopy() {
        val node1 = WorkflowNode("n1", "Node 1", "SMS_TRIGGER")
        val conn1 = WorkflowConnection("n1", "n2")

        val workflow =
            Workflow(
                id = "w_123",
                name = "Test Workflow",
                description = "Desc",
                isEnabled = true,
                icon = "🚀",
                nodes = listOf(node1),
                connections = listOf(conn1),
            )

        assertEquals("w_123", workflow.id)
        assertEquals("Test Workflow", workflow.name)
        assertEquals("🚀", workflow.icon)
        assertTrue(workflow.isEnabled)
        assertEquals(1, workflow.nodes.size)

        val updated = workflow.copy(isEnabled = false, name = "Disabled Workflow", icon = "⚡")
        assertFalse(updated.isEnabled)
        assertEquals("Disabled Workflow", updated.name)
        assertEquals("⚡", updated.icon)
        assertEquals(workflow.id, updated.id)
    }

    @Test
    fun testPreconditionModelDefaults() {
        val internet = Precondition.InternetCheck()
        assertEquals(PreconditionType.INTERNET_CHECK, internet.type)
        assertTrue(internet.isEnabled)
        assertEquals("FAIL_IMMEDIATELY", internet.onFailureAction)
        assertEquals(3, internet.maxRetries)
        assertEquals(30, internet.retryIntervalSeconds)

        val health =
            Precondition.HealthCheck(
                targetUrl = "https://example.com/health",
                onFailureAction = "RETRY",
                maxRetries = 5,
                retryIntervalSeconds = 10,
            )
        assertEquals(PreconditionType.HEALTH_CHECK, health.type)
        assertEquals("https://example.com/health", health.targetUrl)
        assertEquals("RETRY", health.onFailureAction)
        assertEquals(5, health.maxRetries)
        assertEquals(10, health.retryIntervalSeconds)

        val battery =
            Precondition.BatteryCheck(
                minBatteryPercent = 20,
                requireCharging = true,
                icon = "🔋",
            )
        assertEquals(PreconditionType.BATTERY_CHECK, battery.type)
        assertEquals(20, battery.minBatteryPercent)
        assertEquals("🔋", battery.icon)
        assertTrue(battery.requireCharging)
        assertTrue(battery.isEnabled)

        val chained =
            Precondition.ChainCheck(
                name = "Security Vault Guard",
                icon = "🛡️",
            )
        assertEquals("Security Vault Guard", chained.name)
        assertEquals("🛡️", chained.icon)
        assertEquals(PreconditionType.CHAINED_CHECK, chained.type)
    }

    @Test
    fun testCredentialModelDataMap() {
        val cred =
            Credential(
                id = "c_1",
                name = "Test Token",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to "secret_token_123"),
            )

        assertEquals("c_1", cred.id)
        assertEquals("Test Token", cred.name)
        assertEquals(CredentialType.BEARER_TOKEN, cred.type)
        assertEquals("secret_token_123", cred.dataMap["token"])
    }
}
