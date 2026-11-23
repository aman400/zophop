package dev.zophop.ui.preconditions

import dev.zophop.data.PreconditionEntity
import dev.zophop.data.getStepCount
import dev.zophop.data.hasNodeType
import dev.zophop.data.toDomain
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
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
            org.json
                .JSONObject()
                .apply {
                    val arr =
                        org.json.JSONArray().apply {
                            put(
                                org.json.JSONObject().apply {
                                    put("id", "node_gps")
                                    put("name", "GPS Hardware Check")
                                    put("type", "GPS_CHECK")
                                    put("isEnabled", true)
                                    put("configJson", """{"requireGpsEnabled":"true"}""")
                                },
                            )
                        }
                    put("nodes", arr)
                    put("connections", org.json.JSONArray())
                }.toString()

        val entity =
            PreconditionEntity(
                id = "single_node_prec",
                name = "GPS Guard",
                type = PreconditionType.CHAINED_CHECK.name,
                pipelineJson = pipeJson,
            )

        val domain = entity.toDomain()
        assertTrue(domain is Precondition.ChainCheck)
        val chain = domain as Precondition.ChainCheck
        assertEquals(1, chain.nodes.size)
        assertEquals("GPS_CHECK", chain.nodes[0].type)
        assertEquals(0, chain.connections.size)

        // Test helper extensions
        assertTrue(entity.hasNodeType("GPS_CHECK"))
        org.junit.Assert.assertFalse(entity.hasNodeType("VPN_CHECK"))
        assertEquals(1, entity.getStepCount())
    }

    @Test
    fun testMultiNodeChainedPreconditionEntity() {
        val pipeJson =
            org.json
                .JSONObject()
                .apply {
                    val arr =
                        org.json.JSONArray().apply {
                            put(
                                org.json.JSONObject().apply {
                                    put("id", "n1")
                                    put("name", "VPN Check")
                                    put("type", "VPN_CHECK")
                                    put("isEnabled", true)
                                    put("configJson", """{"requireConnected":"true"}""")
                                },
                            )
                            put(
                                org.json.JSONObject().apply {
                                    put("id", "n2")
                                    put("name", "Condition")
                                    put("type", "CONDITION")
                                    put("isEnabled", true)
                                    put("configJson", """{"variable":"vpn_connected","operator":"EQUALS","value":"true"}""")
                                },
                            )
                        }
                    put("nodes", arr)
                    val conns =
                        org.json.JSONArray().apply {
                            put(
                                org.json.JSONObject().apply {
                                    put("fromNodeId", "n1")
                                    put("toNodeId", "n2")
                                    put("outputPort", "main")
                                },
                            )
                        }
                    put("connections", conns)
                }.toString()

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
}
