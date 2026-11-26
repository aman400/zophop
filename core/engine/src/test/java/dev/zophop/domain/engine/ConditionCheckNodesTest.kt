package dev.zophop.domain.engine

import dev.zophop.domain.engine.executors.BatteryCheckExecutor
import dev.zophop.domain.engine.executors.BluetoothCheckExecutor
import dev.zophop.domain.engine.executors.GpsCheckExecutor
import dev.zophop.domain.engine.executors.InternetCheckExecutor
import dev.zophop.domain.engine.executors.SimCheckExecutor
import dev.zophop.domain.engine.executors.VpnCheckExecutor
import dev.zophop.domain.engine.plugin.CoreEngineNodesModule
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
import java.util.UUID

class ConditionCheckNodesTest {
    @Test
    fun testVpnCheckExecutorInspectMode() =
        runTest {
            val executor = VpnCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "vpn_1",
                    name = "VPN Status",
                    type = NodeType.VPN_CHECK,
                    configJson = """{"requireConnected":"false"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertNotNull(context.get("vpn_connected"))
            assertNotNull(context.get("vpn_interface"))
            assertNotNull(context.get("vpn_type"))
        }

    @Test
    fun testVpnCheckExecutorRequireConnectedFailsWhenDisconnected() =
        runTest {
            val executor = VpnCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "vpn_2",
                    name = "VPN Required",
                    type = NodeType.VPN_CHECK,
                    configJson = """{"requireConnected":"true"}""",
                )

            // With null context (simulating disconnected or no VPN service)
            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("not connected to a VPN"))
        }

    @Test
    fun testGpsCheckExecutorInspectMode() =
        runTest {
            val executor = GpsCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "gps_1",
                    name = "GPS Status",
                    type = NodeType.GPS_CHECK,
                    configJson = """{"requireGpsEnabled":"false","fetchCoordinates":"false"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertNotNull(context.get("gps_enabled"))
            assertNotNull(context.get("latitude"))
            assertNotNull(context.get("longitude"))
            assertNotNull(context.get("accuracy"))
            assertNotNull(context.get("maps_url"))
        }

    @Test
    fun testGpsCheckExecutorRequireGpsFailsWhenDisabled() =
        runTest {
            val executor = GpsCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "gps_2",
                    name = "GPS Required",
                    type = NodeType.GPS_CHECK,
                    configJson = """{"requireGpsEnabled":"true"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("GPS") || failed.reason.contains("Location"))
        }

    @Test
    fun testInternetCheckExecutorInspectMode() =
        runTest {
            val executor = InternetCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "net_1",
                    name = "Internet Status",
                    type = NodeType.INTERNET_CHECK,
                    configJson = """{"requireInternet":"false"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertNotNull(context.get("internet_connected"))
            assertNotNull(context.get("network_type"))
            assertNotNull(context.get("is_metered"))
        }

    @Test
    fun testInternetCheckExecutorRequiredNetworkTypeMismatchFails() =
        runTest {
            val executor = InternetCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "net_2",
                    name = "Wi-Fi Only",
                    type = NodeType.INTERNET_CHECK,
                    configJson = """{"requireInternet":"true","requiredNetworkType":"WIFI"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("Wi-Fi connection required"))
        }

    @Test
    fun testBluetoothCheckExecutorInspectMode() =
        runTest {
            val executor = BluetoothCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "bt_1",
                    name = "Bluetooth Status",
                    type = NodeType.BLUETOOTH_CHECK,
                    configJson = """{"requireBluetoothEnabled":"false","requireConnected":"false"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("false", context.get("bluetooth_enabled"))
            assertEquals("false", context.get("bluetooth_connected"))
            assertEquals("none", context.get("connected_device_name"))
            assertEquals("none", context.get("connected_device_address"))
            assertEquals("0", context.get("bonded_device_count"))
        }

    @Test
    fun testBluetoothCheckExecutorRequireEnabledFailsWhenDisabled() =
        runTest {
            val executor = BluetoothCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "bt_2",
                    name = "Bluetooth Required",
                    type = NodeType.BLUETOOTH_CHECK,
                    configJson = """{"requireBluetoothEnabled":"true"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("Bluetooth is turned off"))
        }

    @Test
    fun testSimCheckExecutorInspectMode() =
        runTest {
            val executor = SimCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "sim_1",
                    name = "SIM Status",
                    type = NodeType.SIM_CHECK,
                    configJson = """{"requireSimReady":"false"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("true", context.get("sim_ready"))
            assertNotNull(context.get("carrier_name"))
            assertEquals("1", context.get("sim_count"))
            assertEquals("false", context.get("is_roaming"))
        }

    @Test
    fun testSimCheckExecutorRequiredCarrierMismatchFails() =
        runTest {
            val executor = SimCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "sim_2",
                    name = "SIM Carrier Filter",
                    type = NodeType.SIM_CHECK,
                    configJson = """{"requireSimReady":"true","requiredCarrier":"VerizonWireless"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("does not match required 'VerizonWireless'"))
        }

    @Test
    fun testBatteryCheckExecutorInspectMode() =
        runTest {
            val executor = BatteryCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "bat_1",
                    name = "Battery Status",
                    type = NodeType.BATTERY_CHECK,
                    configJson = """{"minBatteryPercent":"10","requireCharging":"false"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Success)
            assertEquals("100", context.get("battery_percent"))
            assertEquals("false", context.get("is_charging"))
            assertEquals("NOT_CHARGING", context.get("charging_status"))
        }

    @Test
    fun testBatteryCheckExecutorRequireChargingFailsWhenNotCharging() =
        runTest {
            val executor = BatteryCheckExecutor()
            val context = ExecutionContext()
            val node =
                WorkflowNode(
                    id = "bat_2",
                    name = "Battery Charging Required",
                    type = NodeType.BATTERY_CHECK,
                    configJson = """{"minBatteryPercent":"20","requireCharging":"true"}""",
                )

            val result = executor.execute(node, context, null)
            assertTrue(result is NodeExecutionResult.Failed)
            val failed = result as NodeExecutionResult.Failed
            assertTrue(failed.reason.contains("Device is not connected to a charger"))
        }

    @Test
    fun testWorkflowVariableResolverDetectsConditionCheckVariables() {
        val vpnNode = WorkflowNode("n_vpn", "VPN Check", NodeType.VPN_CHECK)
        val vpnVars = WorkflowVariableResolver.getNodeOutputVariables(vpnNode)
        assertTrue(vpnVars.contains("vpn_connected"))
        assertTrue(vpnVars.contains("vpn_interface"))
        assertTrue(vpnVars.contains("vpn_type"))

        val gpsNode = WorkflowNode("n_gps", "GPS Check", NodeType.GPS_CHECK)
        val gpsVars = WorkflowVariableResolver.getNodeOutputVariables(gpsNode)
        assertTrue(gpsVars.contains("gps_enabled"))
        assertTrue(gpsVars.contains("latitude"))
        assertTrue(gpsVars.contains("longitude"))

        val netNode = WorkflowNode("n_net", "Net Check", NodeType.INTERNET_CHECK)
        val netVars = WorkflowVariableResolver.getNodeOutputVariables(netNode)
        assertTrue(netVars.contains("internet_connected"))
        assertTrue(netVars.contains("network_type"))

        val btNode = WorkflowNode("n_bt", "BT Check", NodeType.BLUETOOTH_CHECK)
        val btVars = WorkflowVariableResolver.getNodeOutputVariables(btNode)
        assertTrue(btVars.contains("bluetooth_enabled"))
        assertTrue(btVars.contains("bluetooth_connected"))
        assertTrue(btVars.contains("connected_device_name"))

        val simNode = WorkflowNode("n_sim", "SIM Check", NodeType.SIM_CHECK)
        val simVars = WorkflowVariableResolver.getNodeOutputVariables(simNode)
        assertTrue(simVars.contains("sim_ready"))
        assertTrue(simVars.contains("carrier_name"))

        val batNode = WorkflowNode("n_bat", "Battery Check", NodeType.BATTERY_CHECK)
        val batVars = WorkflowVariableResolver.getNodeOutputVariables(batNode)
        assertTrue(batVars.contains("battery_percent"))
        assertTrue(batVars.contains("is_charging"))
    }

    @Test
    fun testChainedWorkflowConditionCheckVariablePropagation() =
        runTest {
            // Trigger -> BatteryCheck (inspect) -> Condition (check battery_percent == 100)
            val triggerId = UUID.randomUUID().toString()
            val batteryNodeId = UUID.randomUUID().toString()
            val conditionNodeId = UUID.randomUUID().toString()

            val triggerNode =
                WorkflowNode(
                    id = triggerId,
                    name = "Manual Trigger",
                    type = NodeType.MANUAL_TRIGGER,
                    configJson = """{"testBody":"payload"}""",
                )

            val batteryNode =
                WorkflowNode(
                    id = batteryNodeId,
                    name = "Battery Inspect",
                    type = NodeType.BATTERY_CHECK,
                    configJson = """{"minBatteryPercent":"5","requireCharging":"false"}""",
                )

            val conditionNode =
                WorkflowNode(
                    id = conditionNodeId,
                    name = "Check Battery Level",
                    type = NodeType.CONDITION,
                    configJson = """{"variable":"battery_percent","operator":"EQUALS","value":"100"}""",
                )

            val workflow =
                Workflow(
                    id = "wf_check_chain",
                    name = "Check Chain Test",
                    nodes = listOf(triggerNode, batteryNode, conditionNode),
                    connections =
                        listOf(
                            WorkflowConnection(fromNodeId = triggerId, toNodeId = batteryNodeId),
                            WorkflowConnection(fromNodeId = batteryNodeId, toNodeId = conditionNodeId),
                        ),
                )

            val engine = WorkflowEngine(preconditionEvaluator = PreconditionEvaluator(null))
            val context = ExecutionContext()
            val result = engine.executeWorkflow(workflow, context, null)

            assertTrue(result is WorkflowExecutionResult.Success)
            assertEquals("100", context.get("battery_percent"))
            assertEquals("false", context.get("is_charging"))
        }

    @Test
    fun testCoreEngineNodesModuleManifestsContainConditionCheckNodes() {
        val manifests = CoreEngineNodesModule.getManifests()
        val typeIds = manifests.map { it.typeId }

        assertTrue(typeIds.contains(NodeType.VPN_CHECK))
        assertTrue(typeIds.contains(NodeType.GPS_CHECK))
        assertTrue(typeIds.contains(NodeType.INTERNET_CHECK))
        assertTrue(typeIds.contains(NodeType.BLUETOOTH_CHECK))
        assertTrue(typeIds.contains(NodeType.SIM_CHECK))
        assertTrue(typeIds.contains(NodeType.BATTERY_CHECK))

        val gpsManifest = manifests.first { it.typeId == NodeType.GPS_CHECK }
        assertTrue(gpsManifest.requiredPermissions.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))

        val btManifest = manifests.first { it.typeId == NodeType.BLUETOOTH_CHECK }
        assertTrue(btManifest.requiredPermissions.contains("android.permission.BLUETOOTH_CONNECT"))

        val simManifest = manifests.first { it.typeId == NodeType.SIM_CHECK }
        assertTrue(simManifest.requiredPermissions.contains(android.Manifest.permission.READ_PHONE_STATE))
    }
}
