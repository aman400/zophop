package dev.zophop.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApp::class)
class SettingsNotificationUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application
            .getSharedPreferences("zophop_ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun workflowExecutionAlerts_firstToggleWithoutPermission_requestsNativePermissionNotRationale() {
        val viewModel = MainViewModel(application)
        viewModel.toggleShowWorkflowNotifications(false)

        var nativePermissionRequests = 0

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = false,
                onRequestNotificationPermission = { nativePermissionRequests++ },
            )
        }

        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .assertIsOff()
            .performClick()

        composeTestRule.waitForIdle()

        assertEquals(1, nativePermissionRequests)
        composeTestRule.onNodeWithText("Notification Permission Required").assertDoesNotExist()
        assertFalse(viewModel.uiState.value.showWorkflowNotifications)
    }

    @Test
    fun workflowExecutionAlerts_permissionGrantedAfterToggle_automaticallyEnablesAlerts() {
        val viewModel = MainViewModel(application)
        viewModel.toggleShowWorkflowNotifications(false)

        var hasNotifPermission by mutableStateOf(false)
        var nativePermissionRequests = 0

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = hasNotifPermission,
                onRequestNotificationPermission = {
                    nativePermissionRequests++
                    // Simulate user tapping "Allow" on the native system dialog
                    hasNotifPermission = true
                },
            )
        }

        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .assertIsOff()
            .performClick()

        composeTestRule.waitForIdle()

        assertEquals(1, nativePermissionRequests)
        assertTrue(viewModel.uiState.value.showWorkflowNotifications)
        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .assertIsOn()
    }

    @Test
    fun workflowExecutionAlerts_subsequentToggleAfterDenial_opensRationaleDialog() {
        val viewModel = MainViewModel(application)
        viewModel.toggleShowWorkflowNotifications(false)

        var nativePermissionRequests = 0

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = false,
                onRequestNotificationPermission = { nativePermissionRequests++ },
            )
        }

        // First tap: triggers native permission dialog (user denies)
        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, nativePermissionRequests)
        composeTestRule.onNodeWithText("Notification Permission Required").assertDoesNotExist()

        // Second tap while still ungranted: shows rationale dialog to guide user to Settings
        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, nativePermissionRequests)
        composeTestRule.onNodeWithText("Notification Permission Required").assertExists()
        composeTestRule.onNodeWithText("Open Settings").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Notification Permission Required").assertDoesNotExist()
        assertFalse(viewModel.uiState.value.showWorkflowNotifications)
    }

    @Test
    fun workflowExecutionAlerts_whenPermissionGranted_togglesSettingDirectly() {
        val viewModel = MainViewModel(application)
        viewModel.toggleShowWorkflowNotifications(false)

        var nativePermissionRequests = 0

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = true,
                onRequestNotificationPermission = { nativePermissionRequests++ },
            )
        }

        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .assertIsOff()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, nativePermissionRequests)
        assertTrue(viewModel.uiState.value.showWorkflowNotifications)
        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .assertIsOn()

        // Toggling off works too
        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .performClick()
        composeTestRule.waitForIdle()

        assertFalse(viewModel.uiState.value.showWorkflowNotifications)
        composeTestRule
            .onNodeWithTag("workflow_execution_alerts_switch")
            .assertIsOff()
    }
}
