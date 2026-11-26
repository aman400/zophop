package dev.zophop.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.ui.credentials.CredentialsScreen
import dev.zophop.ui.workflows.NodeConfigBottomSheet
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

class TestApp :
    Application(),
    Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        val componentName = ComponentName(packageName, ComponentActivity::class.java.name)
        val hasComponentActivity =
            runCatching { packageManager.getActivityInfo(componentName, 0) }.isSuccess
        if (!hasComponentActivity) {
            val activityInfo =
                ActivityInfo().apply {
                    packageName = this@TestApp.packageName
                    name = ComponentActivity::class.java.name
                    exported = true
                }
            Shadows.shadowOf(packageManager).apply {
                addOrUpdateActivity(activityInfo)
                addIntentFilterForActivity(
                    componentName,
                    IntentFilter(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    },
                )
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApp::class)
class ComposeUiScreensComprehensiveTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSettingsScreenRendersSuccessfully() {
        val viewModel = MainViewModel(application)

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = true,
                onRequestNotificationPermission = {},
            )
        }

        composeTestRule.onNodeWithText("Global Automations Switch").assertExists()
    }

    @Test
    fun testPermissionStatusRowGrantedState() {
        composeTestRule.setContent {
            PermissionStatusRow(
                title = "Location & GPS",
                subtitle = "Required for GPS check condition node",
                isGranted = true,
                onGrantClick = {},
            )
        }

        composeTestRule.onNodeWithText("Location & GPS").assertExists()
        composeTestRule.onNodeWithText("Required for GPS check condition node").assertExists()
        composeTestRule.onNodeWithText("Granted").assertExists()
    }

    @Test
    fun testPermissionStatusRowUngrantedState() {
        var clicked = false
        composeTestRule.setContent {
            PermissionStatusRow(
                title = "Send SMS",
                subtitle = "Required for Send SMS action node",
                isGranted = false,
                grantButtonLabel = "Grant",
                onGrantClick = { clicked = true },
            )
        }

        composeTestRule.onNodeWithText("Send SMS").assertExists()
        composeTestRule.onNodeWithText("Required for Send SMS action node").assertExists()
        composeTestRule.onNodeWithText("Grant").assertExists()
        composeTestRule.onNodeWithText("Grant").performClick()
        assertTrue(clicked)
    }

    @Test
    fun testPermissionStatusRowExternalModuleGrant() {
        var moduleClicked = false
        composeTestRule.setContent {
            PermissionStatusRow(
                title = "android.permission.CAMERA",
                subtitle = "Required by external module: Telegram Actions",
                isGranted = false,
                grantButtonLabel = "Grant in Module",
                onGrantClick = { moduleClicked = true },
            )
        }

        composeTestRule.onNodeWithText("android.permission.CAMERA").assertExists()
        composeTestRule.onNodeWithText("Required by external module: Telegram Actions").assertExists()
        composeTestRule.onNodeWithText("Grant in Module").assertExists()
        composeTestRule.onNodeWithText("Grant in Module").performClick()
        assertTrue(moduleClicked)
    }

    @Test
    fun testCredentialsScreenRendersSuccessfully() {
        val viewModel = MainViewModel(application)

        composeTestRule.setContent {
            CredentialsScreen(
                viewModel = viewModel,
            )
        }

        composeTestRule.onNodeWithText("No saved credentials found. Tap '+ Add Credential' below to add one.").assertExists()
    }

    @Test
    fun testMainAppScreenNavigationAndTabs() {
        val viewModel = MainViewModel(application)

        composeTestRule.setContent {
            MainAppScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = true,
                onRequestNotificationPermission = {},
            )
        }

        composeTestRule.onAllNodesWithText("Workflows")[0].assertExists()
    }

    @Test
    fun testNodeConfigEditorFieldsAreNotAllFocusedInitially() {
        val node =
            WorkflowNode(
                id = "n1",
                name = "Webhook Step",
                type = NodeType.HTTP_ACTION,
                configJson = """{"url":"https://example.com/hook","bodyTemplate":"OTP: ${'$'}{code}"}""",
            )

        composeTestRule.setContent {
            NodeConfigBottomSheet(
                node = node,
                availableVariables = listOf("sender", "code"),
                onDismiss = {},
                onSave = {},
            )
        }

        val textFields = composeTestRule.onAllNodes(hasSetTextAction())
        val count = textFields.fetchSemanticsNodes().size
        assertTrue("Expected multiple editable fields in node config", count >= 2)
        for (i in 0 until count) {
            textFields[i].assertIsNotFocused()
        }

        textFields[0].performClick()
        textFields[0].assertIsFocused()
        for (i in 1 until count) {
            textFields[i].assertIsNotFocused()
        }
    }
}
