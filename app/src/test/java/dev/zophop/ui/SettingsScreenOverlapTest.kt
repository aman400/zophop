package dev.zophop.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zophop.core.ui.AppThemeMode
import dev.zophop.core.ui.AppThemePalette
import dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w320dp-h640dp", application = TestApp::class)
class SettingsScreenOverlapTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    private fun SemanticsNodeInteraction.bounds(): DpRect = getBoundsInRoot()

    private fun assertStrictlyLeftOf(
        leftName: String,
        left: DpRect,
        rightName: String,
        right: DpRect,
        minGapDp: Float = 0f,
    ) {
        assertTrue(
            "Expected '$leftName' ($left) to end at least ${minGapDp}dp before '$rightName' ($right)",
            left.right.value + minGapDp <= right.left.value + 0.5f,
        )
    }

    private fun assertStrictlyAbove(
        topName: String,
        top: DpRect,
        bottomName: String,
        bottom: DpRect,
    ) {
        assertTrue(
            "Expected '$topName' ($top) to sit above '$bottomName' ($bottom)",
            top.bottom.value <= bottom.top.value + 0.5f,
        )
    }

    @Test
    fun settingsScreen_narrowScreen_cardsAndBadgesDoNotOverlap() {
        val viewModel = MainViewModel(application)

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = false,
                onRequestNotificationPermission = {},
            )
        }

        // Alerts switch row must maintain horizontal gap
        val alertsDesc =
            composeTestRule
                .onNodeWithText("Display ongoing status notifications when background workflows execute", useUnmergedTree = true)
                .performScrollTo()
                .bounds()
        val alertsSwitch =
            composeTestRule
                .onNodeWithTag("workflow_execution_alerts_switch", useUnmergedTree = true)
                .bounds()
        assertStrictlyLeftOf("alertsDesc", alertsDesc, "alertsSwitch", alertsSwitch, minGapDp = 12f)

        // Concurrency badges must not collide with descriptions
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Concurrency & Execution Queue"))
        composeTestRule.waitForIdle()

        val queueTimeoutBadge =
            composeTestRule
                .onNodeWithText("10m Timeout", useUnmergedTree = true)
                .performScrollTo()
                .bounds()
        val queueTimeoutDesc =
            composeTestRule
                .onNodeWithText("Duration queued executions wait for preconditions before expiring", useUnmergedTree = true)
                .bounds()
        assertStrictlyLeftOf("queueTimeoutDesc", queueTimeoutDesc, "queueTimeoutBadge", queueTimeoutBadge, minGapDp = 12f)

        // Backup Export / Import buttons
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText("Backup & Data Management"))
        composeTestRule.waitForIdle()

        val exportBtn = composeTestRule.onNodeWithText("Export", useUnmergedTree = true).bounds()
        val importBtn = composeTestRule.onNodeWithText("Import", useUnmergedTree = true).bounds()
        assertStrictlyLeftOf("exportBtn", exportBtn, "importBtn", importBtn, minGapDp = 8f)
    }

    @Test
    fun settingsPermissionsSection_narrowScreen_rowsDoNotOverlapOrTouchZeroGap() {
        val plugin =
            ExternalPluginServiceInfo(
                packageName = "dev.zophop.plugin.very.long.package.name",
                serviceClassName = "PluginService",
                apiVersion = 2,
                manifest =
                    NodePluginManifest(
                        typeId = "PLUGIN_TEST",
                        displayName = "External Telegram Forwarding Module With Long Name",
                        description = "Forwards messages externally",
                        category = NodeCategory.ACTION,
                        requiredPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
                        setupActivity = "dev.zophop.plugin.SetupActivity",
                    ),
            )

        composeTestRule.setContent {
            Column(
                modifier =
                    Modifier
                        .width(300.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                SettingsPermissionsSection(
                    hasSmsPermission = true,
                    onRequestSmsPermission = {},
                    hasSendSmsPermission = false,
                    onRequestSendSmsPermission = {},
                    hasLocationPermission = false,
                    onRequestLocationPermission = {},
                    hasPhoneStatePermission = false,
                    onRequestPhoneStatePermission = {},
                    hasBluetoothPermission = false,
                    onRequestBluetoothPermission = {},
                    hasNotificationPermission = true,
                    onRequestNotificationPermission = {},
                    hasNotificationListenerPermission = false,
                    onRequestNotificationListenerPermission = {},
                    isBatteryOptimized = true,
                    onRequestIgnoreBatteryOptimizations = {},
                    discoveredPlugins = listOf(plugin),
                )
            }
        }

        val receiveSmsDesc =
            composeTestRule
                .onNodeWithText("Required by SMS Trigger nodes to forward incoming text messages", useUnmergedTree = true)
                .bounds()
        val firstGrantedBadge = composeTestRule.onAllNodesWithText("Granted", useUnmergedTree = true)[0].bounds()
        assertStrictlyLeftOf("receiveSmsDesc", receiveSmsDesc, "firstGrantedBadge", firstGrantedBadge, minGapDp = 12f)

        val sendSmsTitle = composeTestRule.onNodeWithText("Send SMS", useUnmergedTree = true).bounds()
        assertStrictlyAbove("firstGrantedBadge", firstGrantedBadge, "sendSmsTitle", sendSmsTitle)

        val sendSmsDesc =
            composeTestRule
                .onNodeWithText("Required by Send SMS Action nodes to dispatch automated outgoing texts", useUnmergedTree = true)
                .bounds()
        val firstGrantButton = composeTestRule.onAllNodesWithText("Grant", useUnmergedTree = true)[0].bounds()
        assertStrictlyLeftOf("sendSmsDesc", sendSmsDesc, "firstGrantButton", firstGrantButton, minGapDp = 12f)

        val fixButton = composeTestRule.onNodeWithText("Fix", useUnmergedTree = true).performScrollTo().bounds()
        val batteryWarning =
            composeTestRule
                .onNodeWithText("Warning (May delay forwarding messages)", useUnmergedTree = true)
                .bounds()
        assertStrictlyLeftOf("batteryWarning", batteryWarning, "fixButton", fixButton, minGapDp = 12f)
    }

    @Test
    fun pluginItemView_narrowScreenLongNameAndPackage_doNotOverlapVersionBadge() {
        val plugin =
            ExternalPluginServiceInfo(
                packageName = "dev.zophop.plugin.extremely.long.external.package.identifier",
                serviceClassName = "PluginService",
                apiVersion = 12,
                manifest =
                    NodePluginManifest(
                        typeId = "PLUGIN_LONG",
                        displayName = "Extremely Long External Plugin Display Name That Wraps Cleanly",
                        description = "Delivers alerts through an external service bridge.",
                        category = NodeCategory.ACTION,
                    ),
            )

        composeTestRule.setContent {
            Box(modifier = Modifier.width(280.dp)) {
                PluginItemView(plugin = plugin)
            }
        }

        val title =
            composeTestRule
                .onNodeWithText("Extremely Long External Plugin Display Name That Wraps Cleanly", useUnmergedTree = true)
                .bounds()
        val versionBadge = composeTestRule.onNodeWithText("v12", useUnmergedTree = true).bounds()
        val description =
            composeTestRule
                .onNodeWithText("Delivers alerts through an external service bridge.", useUnmergedTree = true)
                .bounds()

        assertStrictlyLeftOf("title", title, "versionBadge", versionBadge, minGapDp = 8f)
        assertStrictlyAbove("title", title, "description", description)
        assertStrictlyAbove("versionBadge", versionBadge, "description", description)
    }

    @Test
    fun settingsThemeSection_narrowScreen_controlsAndPalettesDoNotOverlap() {
        var selectedMode = AppThemeMode.SYSTEM
        var selectedPalette = AppThemePalette.EMERALD_AMBER

        composeTestRule.setContent {
            Column(
                modifier =
                    Modifier
                        .width(300.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                SettingsThemeSection(
                    selectedMode = selectedMode,
                    onSelectMode = { selectedMode = it },
                    selectedPalette = selectedPalette,
                    onSelectPalette = { selectedPalette = it },
                )
            }
        }

        val systemBtn = composeTestRule.onNodeWithTag("theme_mode_SYSTEM", useUnmergedTree = true).bounds()
        val lightBtn = composeTestRule.onNodeWithTag("theme_mode_LIGHT", useUnmergedTree = true).bounds()
        val darkBtn = composeTestRule.onNodeWithTag("theme_mode_DARK", useUnmergedTree = true).bounds()
        // Material 3 SingleChoiceSegmentedButtonRow overlaps adjacent buttons by 1dp for shared borders
        assertTrue(
            "Expected systemBtn ($systemBtn) left of lightBtn ($lightBtn)",
            systemBtn.right.value <= lightBtn.left.value + 1.5f,
        )
        assertTrue(
            "Expected lightBtn ($lightBtn) left of darkBtn ($darkBtn)",
            lightBtn.right.value <= darkBtn.left.value + 1.5f,
        )

        val systemLabel = composeTestRule.onNodeWithText("System", useUnmergedTree = true).bounds()
        val lightLabel = composeTestRule.onNodeWithText("Light", useUnmergedTree = true).bounds()
        val darkLabel = composeTestRule.onNodeWithText("Dark", useUnmergedTree = true).bounds()
        assertStrictlyLeftOf("systemLabel", systemLabel, "lightLabel", lightLabel, minGapDp = 12f)
        assertStrictlyLeftOf("lightLabel", lightLabel, "darkLabel", darkLabel, minGapDp = 12f)

        val emeraldCard =
            composeTestRule
                .onNodeWithTag("theme_palette_EMERALD_AMBER", useUnmergedTree = true)
                .performScrollTo()
                .bounds()
        val oceanCard =
            composeTestRule
                .onNodeWithTag("theme_palette_OCEAN_CYAN", useUnmergedTree = true)
                .performScrollTo()
                .bounds()
        val indigoCard =
            composeTestRule
                .onNodeWithTag("theme_palette_INDIGO_VIOLET", useUnmergedTree = true)
                .performScrollTo()
                .bounds()
        val crimsonCard =
            composeTestRule
                .onNodeWithTag("theme_palette_CRIMSON_CORAL", useUnmergedTree = true)
                .performScrollTo()
                .bounds()

        assertStrictlyLeftOf("emeraldCard", emeraldCard, "oceanCard", oceanCard, minGapDp = 8f)
        assertStrictlyLeftOf("indigoCard", indigoCard, "crimsonCard", crimsonCard, minGapDp = 8f)
        assertStrictlyAbove("emeraldCard", emeraldCard, "indigoCard", indigoCard)
        assertStrictlyAbove("oceanCard", oceanCard, "crimsonCard", crimsonCard)
    }

    @Test
    fun settingsScreen_selectingThemeAndPalette_updatesViewModelAndAppliedColorScheme() {
        val viewModel = MainViewModel(application)
        var observedPrimary = Color.Unspecified

        composeTestRule.setContent {
            val uiState by viewModel.uiState.collectAsState()
            val mode = AppThemeMode.fromId(uiState.themeMode)
            val palette = AppThemePalette.fromId(uiState.themePalette)
            ZopHopTheme(themeMode = mode, themePalette = palette) {
                observedPrimary = MaterialTheme.colorScheme.primary
                SettingsScreen(
                    viewModel = viewModel,
                    hasSmsPermission = true,
                    onRequestSmsPermission = {},
                    hasNotificationPermission = true,
                    onRequestNotificationPermission = {},
                )
            }
        }

        assertEquals(
            AppThemePalette.EMERALD_AMBER.lightColorScheme.primary.value
                .toLong(),
            observedPrimary.value.toLong(),
        )

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("theme_mode_DARK"))
        composeTestRule.onNodeWithTag("theme_mode_DARK").performClick()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("theme_palette_INDIGO_VIOLET"))
        composeTestRule.onNodeWithTag("theme_palette_INDIGO_VIOLET").performClick()
        composeTestRule.waitForIdle()

        assertEquals("DARK", viewModel.uiState.value.themeMode)
        assertEquals("INDIGO_VIOLET", viewModel.uiState.value.themePalette)
        assertEquals(
            AppThemePalette.INDIGO_VIOLET.darkColorScheme.primary.value
                .toLong(),
            observedPrimary.value.toLong(),
        )
    }
}
