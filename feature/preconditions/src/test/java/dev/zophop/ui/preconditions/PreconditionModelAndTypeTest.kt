package dev.zophop.ui.preconditions

import dev.zophop.data.PreconditionEntity
import dev.zophop.data.getStepCount
import dev.zophop.data.hasNodeType
import dev.zophop.data.toDomain
import dev.zophop.domain.model.AppJson
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowGraph
import dev.zophop.domain.model.WorkflowNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreconditionModelAndTypeTest {
    @Test
    fun testInternetPreconditionEntityToDomain() {
        val entity =
            PreconditionEntity(
                id = "test_1",
                name = "Test Internet",
                type = PreconditionType.INTERNET_CHECK.name,
                isEnabled = true,
                onFailureAction = "RETRY",
                maxRetries = 3,
                retryIntervalSeconds = 15,
            )
        val domain = entity.toDomain()
        assertTrue(domain is Precondition.InternetCheck)
        assertEquals(3, domain.maxRetries)
        assertTrue(domain.isEnabled)
    }

    @Test
    fun testQueuePreconditionEntityToDomain() {
        val entity =
            PreconditionEntity(
                id = "test_queue",
                name = "Test Queue Precondition",
                type = PreconditionType.INTERNET_CHECK.name,
                isEnabled = true,
                onFailureAction = "QUEUE",
                queueTimeoutMinutes = 25,
            )
        val domain = entity.toDomain()
        assertTrue(domain is Precondition.InternetCheck)
        assertEquals("QUEUE", domain.onFailureAction)
        assertEquals(25, domain.queueTimeoutMinutes)
    }

    @Test
    fun testRetryStrategiesPreconditionEntityToDomain() {
        val expEntity =
            PreconditionEntity(
                id = "test_exp",
                name = "Exponential Backoff Check",
                type = PreconditionType.HEALTH_CHECK.name,
                onFailureAction = "RETRY",
                maxRetries = 4,
                retryIntervalSeconds = 15,
                retryStrategy = "EXPONENTIAL",
            )
        val expDomain = expEntity.toDomain()
        assertTrue(expDomain is Precondition.HealthCheck)
        assertEquals("EXPONENTIAL", expDomain.retryStrategy)
        assertEquals(4, expDomain.maxRetries)
        assertEquals(15, expDomain.retryIntervalSeconds)

        val randEntity =
            PreconditionEntity(
                id = "test_rand",
                name = "Randomized Backoff Check",
                type = PreconditionType.BATTERY_CHECK.name,
                onFailureAction = "RETRY",
                maxRetries = 3,
                retryIntervalSeconds = 10,
                retryStrategy = "RANDOMIZED",
            )
        val randDomain = randEntity.toDomain()
        assertTrue(randDomain is Precondition.BatteryCheck)
        assertEquals("RANDOMIZED", randDomain.retryStrategy)
    }

    @Test
    fun testVpnPreconditionEntityToDomain() {
        val entity =
            PreconditionEntity(
                id = "test_2",
                name = "Test VPN",
                type = PreconditionType.VPN_CHECK.name,
                isEnabled = false,
            )
        val domain = entity.toDomain()
        assertTrue(domain is Precondition.VpnCheck)
        assertEquals(false, domain.isEnabled)
    }

    @Test
    fun testBatteryPreconditionEntityToDomain() {
        val entity =
            PreconditionEntity(
                id = "test_battery",
                name = "Test Battery",
                type = PreconditionType.BATTERY_CHECK.name,
                minBatteryPercent = 20,
                requireCharging = true,
                isEnabled = true,
            )
        val domain = entity.toDomain()
        assertTrue(domain is Precondition.BatteryCheck)
        val batteryCheck = domain as Precondition.BatteryCheck
        assertEquals(20, batteryCheck.minBatteryPercent)
        assertTrue(batteryCheck.requireCharging)
        assertTrue(batteryCheck.isEnabled)
    }

    @Test
    fun testSingleNodeChainedPreconditionEntity() {
        val pipeJson =
            AppJson.encodeToString(
                WorkflowGraph(
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "node_gps",
                                name = "GPS Hardware Check",
                                type = "GPS_CHECK",
                                isEnabled = true,
                                configJson = """{"requireGpsEnabled":"true"}""",
                            ),
                        ),
                    connections = emptyList(),
                ),
            )

        val entity =
            PreconditionEntity(
                id = "single_node_prec",
                name = "GPS Guard",
                type = PreconditionType.CHAINED_CHECK.name,
                icon = "📍",
                pipelineJson = pipeJson,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.ChainCheck)
        val chain = domain as Precondition.ChainCheck
        assertEquals(1, chain.nodes.size)
        assertEquals("GPS_CHECK", chain.nodes[0].type)
        assertEquals(0, chain.connections.size)
        assertEquals("📍", chain.icon)

        // Test helper extensions
        assertTrue(entity.hasNodeType("GPS_CHECK"))
        org.junit.Assert.assertFalse(entity.hasNodeType("VPN_CHECK"))
        assertEquals(1, entity.getStepCount())
    }

    @Test
    fun testMultiNodeChainedPreconditionEntity() {
        val pipeJson =
            AppJson.encodeToString(
                WorkflowGraph(
                    nodes =
                        listOf(
                            WorkflowNode(
                                id = "n1",
                                name = "VPN Check",
                                type = "VPN_CHECK",
                                isEnabled = true,
                                configJson = """{"requireConnected":"true"}""",
                            ),
                            WorkflowNode(
                                id = "n2",
                                name = "Condition",
                                type = "CONDITION",
                                isEnabled = true,
                                configJson = """{"variable":"vpn_connected","operator":"EQUALS","value":"true"}""",
                            ),
                        ),
                    connections =
                        listOf(
                            WorkflowConnection(
                                fromNodeId = "n1",
                                toNodeId = "n2",
                                outputPort = "main",
                            ),
                        ),
                ),
            )

        val entity =
            PreconditionEntity(
                id = "multi_prec",
                name = "VPN & Condition Guard",
                type = PreconditionType.CHAINED_CHECK.name,
                pipelineJson = pipeJson,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.ChainCheck)
        val chain = domain as Precondition.ChainCheck
        assertEquals(2, chain.nodes.size)
        assertEquals(1, chain.connections.size)

        assertTrue(entity.hasNodeType("VPN_CHECK"))
        assertTrue(entity.hasNodeType("CONDITION"))
        org.junit.Assert.assertFalse(entity.hasNodeType("INTERNET_CHECK"))
        assertEquals(2, entity.getStepCount())
    }

    @Test
    fun testInitialPreconditionAllowedCheckTypes() {
        val builtIn =
            dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
                .getBuiltInManifests()

        val conditionCheckNodes =
            builtIn.filter {
                it.category == dev.zophop.plugin.api.NodeCategory.LOGIC &&
                    (it.typeId.contains("CHECK", ignoreCase = true) || it.displayName.contains("Check", ignoreCase = true))
            }
        val httpNodes =
            builtIn.filter {
                it.typeId.contains("HTTP_ACTION", ignoreCase = true) || it.typeId.equals("HTTP", ignoreCase = true)
            }

        // Must include all system & hardware checks
        assertTrue(conditionCheckNodes.any { it.typeId == "INTERNET_CHECK" })
        assertTrue(conditionCheckNodes.any { it.typeId == "VPN_CHECK" })
        assertTrue(conditionCheckNodes.any { it.typeId == "GPS_CHECK" })
        assertTrue(conditionCheckNodes.any { it.typeId == "BATTERY_CHECK" })
        assertTrue(conditionCheckNodes.any { it.typeId == "BLUETOOTH_CHECK" })
        assertTrue(conditionCheckNodes.any { it.typeId == "SIM_CHECK" })

        // Must include HTTP / HTTPS node
        assertTrue(httpNodes.any { it.typeId == "HTTP_ACTION" })

        // Initial check options must NOT include SMS, Notifications, Condition, or Parsers
        val initialAllowedTypeIds = (conditionCheckNodes + httpNodes).map { it.typeId }
        org.junit.Assert.assertFalse(initialAllowedTypeIds.contains("CONDITION"))
        org.junit.Assert.assertFalse(initialAllowedTypeIds.contains("SEND_SMS_ACTION"))
        org.junit.Assert.assertFalse(initialAllowedTypeIds.contains("SEND_NOTIFICATION_ACTION"))
        org.junit.Assert.assertFalse(initialAllowedTypeIds.contains("JSON_PARSER"))
        org.junit.Assert.assertFalse(initialAllowedTypeIds.contains("REGEX_PARSER"))
    }

    @Test
    fun testCountMissingExternalPluginsForPreconditions() {
        val builtInPrecondition =
            PreconditionEntity(
                id = "p_builtin",
                name = "Internet Guard",
                type = PreconditionType.INTERNET_CHECK.name,
            )
        assertEquals(0, countMissingExternalPlugins(builtInPrecondition, emptySet()))

        val chainJson =
            AppJson.encodeToString(
                WorkflowGraph(
                    nodes =
                        listOf(
                            WorkflowNode("n1", "Internet", "INTERNET_CHECK"),
                            WorkflowNode("n2", "Telegram", "dev.zophop.plugin.telegram.TELEGRAM_ACTION"),
                            WorkflowNode("n3", "SMTP", "dev.zophop.plugin.smtp.SMTP_ACTION"),
                            WorkflowNode("n4", "Telegram 2", "dev.zophop.plugin.telegram.TELEGRAM_ACTION"),
                        ),
                    connections = emptyList(),
                ),
            )
        val externalChainPrecondition =
            PreconditionEntity(
                id = "p_chain_ext",
                name = "External Chain Guard",
                type = PreconditionType.CHAINED_CHECK.name,
                pipelineJson = chainJson,
            )

        // Returns 0 while plugin discovery has not completed yet (null)
        assertEquals(0, countMissingExternalPlugins(externalChainPrecondition, null))
        // Counts distinct missing external plugin node types (Telegram + SMTP = 2)
        assertEquals(2, countMissingExternalPlugins(externalChainPrecondition, emptySet()))
        // Partially installed reduces missing count
        assertEquals(
            1,
            countMissingExternalPlugins(
                externalChainPrecondition,
                setOf("dev.zophop.plugin.telegram.TELEGRAM_ACTION"),
            ),
        )
        // All installed clears missing count
        assertEquals(
            0,
            countMissingExternalPlugins(
                externalChainPrecondition,
                setOf(
                    "dev.zophop.plugin.telegram.TELEGRAM_ACTION",
                    "dev.zophop.plugin.smtp.SMTP_ACTION",
                ),
            ),
        )
    }
}
