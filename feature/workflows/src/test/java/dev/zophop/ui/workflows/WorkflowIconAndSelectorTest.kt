package dev.zophop.ui.workflows

import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowIconAndSelectorTest {
    @Test
    fun testWorkflowIconChangeDetection() {
        val originalWorkflow =
            Workflow(
                id = "wf_icon_1",
                name = "Automated Messenger",
                icon = "🤖",
            )

        // Same icon means no changes
        val sameWorkflow = originalWorkflow.copy()
        assertEquals(originalWorkflow, sameWorkflow)

        // Changed icon
        val updatedIconWorkflow = originalWorkflow.copy(icon = "⚡")
        assertTrue(originalWorkflow != updatedIconWorkflow)
        assertEquals("⚡", updatedIconWorkflow.icon)

        // Cleared icon (default)
        val clearedIconWorkflow = originalWorkflow.copy(icon = "")
        assertTrue(originalWorkflow != clearedIconWorkflow)
        assertEquals("", clearedIconWorkflow.icon)
    }

    @Test
    fun testPreconditionBadgeIconFormattingDefaults() {
        fun formatBadge(prec: Precondition): String {
            val defaultIcon =
                when (prec) {
                    is Precondition.InternetCheck -> "🌐"
                    is Precondition.VpnCheck -> "🔐"
                    is Precondition.HealthCheck -> "🩺"
                    is Precondition.SimCheck -> "📱"
                    is Precondition.GpsCheck -> "📍"
                    is Precondition.BatteryCheck -> "🔋"
                    is Precondition.ChainCheck -> "⛓️"
                }
            val effectiveIcon = prec.icon.ifBlank { defaultIcon }
            val badgeLabel =
                when (prec) {
                    is Precondition.InternetCheck -> "Internet"
                    is Precondition.VpnCheck -> "VPN"
                    is Precondition.HealthCheck -> "Healthcheck"
                    is Precondition.SimCheck -> "SIM"
                    is Precondition.GpsCheck -> "GPS"
                    is Precondition.BatteryCheck -> "Battery (>= ${prec.minBatteryPercent}%)"
                    is Precondition.ChainCheck -> {
                        val rawName =
                            prec.name.ifBlank {
                                if (prec.nodes.size == 1) prec.nodes[0].name else "Chained"
                            }
                        rawName.removePrefix("⛓️").removePrefix("🔒").removePrefix("🛡️").trim()
                    }
                }
            return "$effectiveIcon $badgeLabel" + if (!prec.isEnabled) " (Disabled)" else ""
        }

        // Test default badges
        assertEquals("🌐 Internet", formatBadge(Precondition.InternetCheck()))
        assertEquals("🔐 VPN", formatBadge(Precondition.VpnCheck()))
        assertEquals("🩺 Healthcheck", formatBadge(Precondition.HealthCheck()))
        assertEquals("📱 SIM", formatBadge(Precondition.SimCheck()))
        assertEquals("📍 GPS", formatBadge(Precondition.GpsCheck()))
        assertEquals("🔋 Battery (>= 15%)", formatBadge(Precondition.BatteryCheck(minBatteryPercent = 15)))
        assertEquals("⛓️ Security Rule", formatBadge(Precondition.ChainCheck(name = "Security Rule")))

        // Test disabled suffix
        assertEquals("🌐 Internet (Disabled)", formatBadge(Precondition.InternetCheck(isEnabled = false)))
        assertEquals("⛓️ Guard (Disabled)", formatBadge(Precondition.ChainCheck(name = "Guard", isEnabled = false)))
    }

    @Test
    fun testPreconditionBadgeCustomIconOverride() {
        fun formatBadge(prec: Precondition): String {
            val defaultIcon =
                when (prec) {
                    is Precondition.InternetCheck -> "🌐"
                    is Precondition.VpnCheck -> "🔐"
                    is Precondition.HealthCheck -> "🩺"
                    is Precondition.SimCheck -> "📱"
                    is Precondition.GpsCheck -> "📍"
                    is Precondition.BatteryCheck -> "🔋"
                    is Precondition.ChainCheck -> "⛓️"
                }
            val effectiveIcon = prec.icon.ifBlank { defaultIcon }
            val badgeLabel =
                when (prec) {
                    is Precondition.InternetCheck -> "Internet"
                    is Precondition.VpnCheck -> "VPN"
                    is Precondition.HealthCheck -> "Healthcheck"
                    is Precondition.SimCheck -> "SIM"
                    is Precondition.GpsCheck -> "GPS"
                    is Precondition.BatteryCheck -> "Battery (>= ${prec.minBatteryPercent}%)"
                    is Precondition.ChainCheck -> {
                        val rawName =
                            prec.name.ifBlank {
                                if (prec.nodes.size == 1) prec.nodes[0].name else "Chained"
                            }
                        rawName.removePrefix("⛓️").removePrefix("🔒").removePrefix("🛡️").trim()
                    }
                }
            return "$effectiveIcon $badgeLabel" + if (!prec.isEnabled) " (Disabled)" else ""
        }

        // Test custom icon overrides
        assertEquals("🚀 Internet", formatBadge(Precondition.InternetCheck(icon = "🚀")))
        assertEquals("🛡️ VPN", formatBadge(Precondition.VpnCheck(icon = "🛡️")))
        assertEquals("❤️ Healthcheck", formatBadge(Precondition.HealthCheck(icon = "❤️")))
        assertEquals("📶 SIM", formatBadge(Precondition.SimCheck(icon = "📶")))
        assertEquals("🚗 GPS", formatBadge(Precondition.GpsCheck(icon = "🚗")))
        assertEquals("⚡ Battery (>= 50%)", formatBadge(Precondition.BatteryCheck(minBatteryPercent = 50, icon = "⚡")))
        assertEquals("🪄 High Priority Guard", formatBadge(Precondition.ChainCheck(name = "High Priority Guard", icon = "🪄")))

        // Test emoji prefix stripping from name when custom icon is supplied
        assertEquals("🛡️ Custom Guard", formatBadge(Precondition.ChainCheck(name = "🛡️ Custom Guard", icon = "🛡️")))
        assertEquals("⚡ Custom Guard", formatBadge(Precondition.ChainCheck(name = "🔒 Custom Guard", icon = "⚡")))
    }

    @Test
    fun testPreconditionDropdownItemFormatting() {
        fun formatDropdownItem(prec: PreconditionEntity): String {
            val iconPrefix = if (prec.icon.isNotBlank()) "${prec.icon} " else ""
            val summary = WorkflowUiHelper.getPreconditionPipelineSummary(prec.type, prec.pipelineJson).second
            return "$iconPrefix${prec.name} ($summary)"
        }

        val withIcon =
            PreconditionEntity(
                id = "p1",
                name = "Office VPN",
                type = PreconditionType.VPN_CHECK.name,
                icon = "🔐",
            )
        assertEquals("🔐 Office VPN (VPN Connection Check)", formatDropdownItem(withIcon))

        val withoutIcon =
            PreconditionEntity(
                id = "p2",
                name = "Global Internet",
                type = PreconditionType.INTERNET_CHECK.name,
                icon = "",
            )
        assertEquals("Global Internet (Network Internet Check)", formatDropdownItem(withoutIcon))
    }

    @Test
    fun testPreconditionMultiSelectionToggleLogic() {
        val attachedIds = mutableSetOf("prec_1", "prec_2")

        fun toggleId(id: String) {
            if (attachedIds.contains(id)) {
                attachedIds.remove(id)
            } else {
                attachedIds.add(id)
            }
        }

        // Remove existing
        toggleId("prec_1")
        assertFalse(attachedIds.contains("prec_1"))
        assertTrue(attachedIds.contains("prec_2"))

        // Add new
        toggleId("prec_3")
        assertTrue(attachedIds.contains("prec_3"))
        assertEquals(setOf("prec_2", "prec_3"), attachedIds)

        // Toggle back
        toggleId("prec_1")
        assertTrue(attachedIds.contains("prec_1"))
        assertEquals(3, attachedIds.size)
    }

    @Test
    fun testWorkflowNodeWithMultiplePreconditionsPreservesOrderAndIcons() {
        val prec1 = Precondition.InternetCheck(id = "p1", icon = "🌐")
        val prec2 = Precondition.VpnCheck(id = "p2", icon = "🔐")
        val prec3 = Precondition.ChainCheck(id = "p3", name = "Battery & Health", icon = "⚡")

        val node =
            WorkflowNode(
                id = "n_test",
                name = "Action Step",
                type = NodeType.HTTP_ACTION,
                preconditions = listOf(prec1, prec2, prec3),
            )

        assertEquals(3, node.preconditions.size)
        assertEquals("🌐", node.preconditions[0].icon)
        assertEquals("🔐", node.preconditions[1].icon)
        assertEquals("⚡", node.preconditions[2].icon)
    }
}
