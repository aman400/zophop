package dev.zophop.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.core.ui.AppThemeMode
import dev.zophop.core.ui.AppThemePalette
import dev.zophop.core.ui.ZopHopTheme
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.ui.credentials.BiometricSecuritySuggestionBanner
import dev.zophop.ui.workflows.CompleteExecutionLogDialog
import dev.zophop.ui.workflows.CompleteWorkflowExecutionReportDialog
import dev.zophop.ui.workflows.NodeConfigBottomSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp", application = TestApp::class)
class ExecutionLogAndCurlImportUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun completeExecutionLogDialog_rendersStructuredSectionsJsonPrettyPrintAndRawToggle() {
        val logText =
            """
            HTTP POST https://api.example.com/webhook -> 200 OK (45ms)
            --- Request Details ---
            Method: POST
            URL: https://api.example.com/webhook
            --- Response Body ---
            {"status":"accepted","count":3}
            """.trimIndent()

        val step =
            NodeExecutionStep(
                nodeId = "http_1",
                nodeName = "Webhook Dispatch",
                nodeType = NodeType.HTTP_ACTION,
                state = NodeStepState.SUCCESS,
                logMessage = logText,
                executedAt = 1_700_000_000_000L,
                durationMs = 45L,
            )

        composeTestRule.setContent {
            CompleteExecutionLogDialog(
                step = step,
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Complete Execution Log").assertExists()
        composeTestRule.onNodeWithText("Request Details").assertExists()
        composeTestRule.onNodeWithText("Response Body").assertExists()
        composeTestRule.onNodeWithText("JSON").assertExists()
        composeTestRule.onNodeWithText("Copy Log").assertExists()

        // Switch to Raw Log view
        composeTestRule.onNodeWithText("Raw Log").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(logText).assertExists()
    }

    @Test
    fun completeWorkflowExecutionReportDialog_filtersStepsByOutcome() {
        val steps =
            listOf(
                NodeExecutionStep(
                    nodeId = "n1",
                    nodeName = "SMS Trigger",
                    nodeType = NodeType.SMS_TRIGGER,
                    state = NodeStepState.SUCCESS,
                    logMessage = "SMS trigger matched sender: BANK",
                    durationMs = 12L,
                ),
                NodeExecutionStep(
                    nodeId = "n2",
                    nodeName = "Webhook",
                    nodeType = NodeType.HTTP_ACTION,
                    state = NodeStepState.FAILED,
                    logMessage = "HTTP POST failed with status 500",
                    errorReason = "HTTP 500",
                    durationMs = 180L,
                ),
            )

        composeTestRule.setContent {
            CompleteWorkflowExecutionReportDialog(
                workflowName = "Bank OTP Forwarder",
                workflowId = "WF-1",
                executionId = "EX-1",
                status = "FAILED",
                steps = steps,
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("1/2 passed • 1 failed").assertExists()
        composeTestRule.onNodeWithText("1. SMS Trigger").assertExists()
        composeTestRule.onNodeWithText("2. Webhook").assertExists()

        composeTestRule.onNodeWithText("Errors (1)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("1. SMS Trigger").assertDoesNotExist()
        composeTestRule.onNodeWithText("1. Webhook").assertExists()
    }

    @Test
    fun httpNodeConfig_importFromCurl_populatesFieldsAndSavesConfig() {
        var savedNode: WorkflowNode? = null
        val httpNode =
            WorkflowNode(
                id = "http_node_1",
                name = "My Webhook",
                type = NodeType.HTTP_ACTION,
                configJson = """{"url":"https://old.example.com","method":"GET"}""",
            )

        composeTestRule.setContent {
            NodeConfigBottomSheet(
                node = httpNode,
                onDismiss = {},
                onSave = { savedNode = it },
            )
        }

        composeTestRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        composeTestRule.onAllNodesWithText("Import cURL")[1].performClick()
        composeTestRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        composeTestRule.onNodeWithText("Import from cURL").assertExists()
        composeTestRule.onNodeWithText("Apply to Node").assertIsNotEnabled()

        val sampleCurl =
            """curl -X PATCH "https://api.service.io/v2/hooks?source=sms" -H "X-Token: abc" -d '{"code":"123456"}'"""
        composeTestRule.onNodeWithText("Paste cURL Command").performTextInput(sampleCurl)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Ready to Import").assertExists()
        composeTestRule.onNodeWithText("Apply to Node").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        composeTestRule.onNodeWithText("Save Node").performClick()
        composeTestRule.waitForIdle()

        assertNotNull(savedNode)
        val savedConfig = parseJsonObjectSafe(savedNode!!.configJson)
        assertEquals("https://api.service.io/v2/hooks", savedConfig.optString("url"))
        assertEquals("PATCH", savedConfig.optString("method"))
        assertEquals("source=sms", savedConfig.optString("queryParams"))
        assertTrue(savedConfig.optString("headers").contains("X-Token: abc"))
        assertEquals("JSON", savedConfig.optString("bodyType"))
        assertEquals("""{"code":"123456"}""", savedConfig.optString("bodyTemplate"))
    }

    @Test
    fun biometricSecuritySuggestionBanner_darkDynamicTheme_rendersAndHandlesActions() {
        var enabledClicked = false
        composeTestRule.setContent {
            ZopHopTheme(
                themeMode = AppThemeMode.DARK,
                themePalette = AppThemePalette.DYNAMIC,
            ) {
                BiometricSecuritySuggestionBanner(
                    onEnableClick = { enabledClicked = true },
                    onDismissClick = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Enhance Vault Security").assertExists()
        composeTestRule
            .onNodeWithText("Enable Biometric Lock")
            .assertExists()
            .performClick()
        assertTrue(enabledClicked)
    }
}
