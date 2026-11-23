package dev.zophop.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import dev.zophop.data.DeliveryNode
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class MainViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var application: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(application)

        viewModel = MainViewModel(application)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialUiState() =
        runTest {
            val state = viewModel.uiState.value
            assertNotNull(state)
            assertTrue(state.isRegexValid)
            assertTrue(state.isUrlValid)
        }

    @Test
    fun testDeliveryNodeCrudOperations() =
        runTest {
            val node1 =
                DeliveryNode(
                    id = "n_100",
                    name = "Test Webhook Target",
                    serverUrl = "https://ntfy.sh",
                    topic = "test_topic",
                    isEnabled = true,
                )

            viewModel.addNode(node1)
            var state = viewModel.uiState.value
            assertEquals(1, state.nodes.size)
            assertEquals("n_100", state.nodes[0].id)
            assertEquals(1, state.activeNodes.size)

            // Update node
            val updatedNode1 = node1.copy(name = "Updated Webhook Target", isEnabled = false)
            viewModel.updateNode(updatedNode1)
            state = viewModel.uiState.value
            assertEquals("Updated Webhook Target", state.nodes[0].name)
            assertEquals(0, state.activeNodes.size)

            // Delete node
            viewModel.deleteNode("n_100")
            state = viewModel.uiState.value
            assertTrue(state.nodes.isEmpty())
        }

    @Test
    fun testRegexFiltersValidation() =
        runTest {
            viewModel.updateKeywordRegex("(?i)OTP|verification")
            assertTrue(viewModel.uiState.value.isRegexValid)
            assertEquals("(?i)OTP|verification", viewModel.uiState.value.keywordRegex)

            // Test invalid regex
            viewModel.updateKeywordRegex("[unclosed-group")
            assertFalse(viewModel.uiState.value.isRegexValid)
        }

    @Test
    fun testSettingsTogglesAndParameters() =
        runTest {
            viewModel.toggleMasterSwitch(true)
            assertTrue(viewModel.uiState.value.isEnabled)

            viewModel.toggleAllowInsecureHttp(true)
            assertTrue(viewModel.uiState.value.allowInsecureHttp)

            viewModel.toggleForwardNotifications(true)
            assertTrue(viewModel.uiState.value.forwardNotifications)

            viewModel.toggleShowWorkflowNotifications(false)
            assertFalse(viewModel.uiState.value.showWorkflowNotifications)

            viewModel.toggleShowWorkflowNotifications(true)
            assertTrue(viewModel.uiState.value.showWorkflowNotifications)

            viewModel.toggleLockCredentialsWithBiometric(true)
            assertTrue(viewModel.uiState.value.lockCredentialsWithBiometric)

            viewModel.toggleGlobalWorkflowsEnabled(false)
            assertFalse(viewModel.uiState.value.globalWorkflowsEnabled)

            viewModel.dismissBiometricBanner()
            assertTrue(viewModel.uiState.value.dismissedBiometricBanner)

            viewModel.updateMaxParallelExecutions(5)
            assertEquals(5, viewModel.uiState.value.maxParallelExecutions)

            viewModel.updateMaxExecutionQueueSize(50)
            assertEquals(50, viewModel.uiState.value.maxExecutionQueueSize)

            viewModel.updateAutoClearMinutes("720")
            assertEquals("720", viewModel.uiState.value.autoClearMinutes)
        }

    @Test
    fun testWorkflowRepositoryOperations() =
        runTest {
            val workflow =
                Workflow(
                    id = "wf_unit_1",
                    name = "Test SMS Forwarder",
                    description = "Forwarder description",
                    isEnabled = true,
                    nodes =
                        listOf(
                            WorkflowNode("trigger", "SMS Trigger", NodeType.SMS_TRIGGER),
                            WorkflowNode(
                                "action",
                                "ntfy Action",
                                NodeType.NTFY_ACTION,
                                configJson = """{"serverUrl":"https://ntfy.sh","topic":"test"}""",
                            ),
                        ),
                )

            viewModel.saveWorkflow(workflow)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.setWorkflowEnabled("wf_unit_1", false)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteWorkflow("wf_unit_1")
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun testCredentialAndPreconditionManagement() =
        runTest {
            val cred =
                Credential(
                    id = "c_99",
                    name = "API Key Vault",
                    type = CredentialType.BEARER_TOKEN,
                    dataMap = mapOf("token" to "secret-api-key-value"),
                )

            viewModel.saveCredential(cred)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deleteCredential("c_99")
            testDispatcher.scheduler.advanceUntilIdle()

            val prec =
                PreconditionEntity(
                    id = "p_88",
                    name = "Wi-Fi Check",
                    type = "HEALTH_CHECK",
                    isEnabled = true,
                )

            viewModel.savePrecondition(prec)
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.deletePrecondition("p_88")
            testDispatcher.scheduler.advanceUntilIdle()
        }

    @Test
    fun testClearAllEventsAndCancelWorkflow() =
        runTest {
            viewModel.clearAllEvents()
            viewModel.clearLogsForWorkflow("wf_unit_1")
            viewModel.cancelWorkflowExecution("wf_unit_1")
            viewModel.clearActiveExecutionState()
            assertNull(viewModel.activeExecutionState.value)
        }
}
