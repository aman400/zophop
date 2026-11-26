package dev.zophop.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import androidx.biometric.BiometricManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import dev.zophop.core.ui.components.BiometricAuthHelper
import dev.zophop.core.ui.components.CredentialBottomSheet
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType
import dev.zophop.ui.credentials.CredentialsScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowToast

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = TestApp::class)
class CredentialBiometricUnlockTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<FragmentActivity>()

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(application)
    }

    private fun configureBiometricEnvironment(
        isDeviceSecure: Boolean,
        canAuthenticate: Boolean,
    ) {
        Shadows.shadowOf(application.packageManager).setSystemFeature(
            PackageManager.FEATURE_FINGERPRINT,
            canAuthenticate,
        )
        val keyguardManager = application.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        Shadows.shadowOf(keyguardManager).setIsDeviceSecure(isDeviceSecure)
        val systemBiometricManager =
            application.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        val shadowBiometric = Shadow.extract<ShadowBiometricManager>(systemBiometricManager)
        shadowBiometric.setCanAuthenticate(canAuthenticate)
    }

    private fun renderedText(nodeLabel: String): String {
        val node = composeTestRule.onNodeWithText(nodeLabel).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.layoutInput?.text?.text.orEmpty()
    }

    @Test
    fun testFindFragmentActivity_unwrapsContextWrappers() {
        val activity = composeTestRule.activity
        val themed = ContextThemeWrapper(activity, android.R.style.Theme_Material_Light)
        val doubleWrapped = ContextWrapper(themed)

        assertSame(activity, BiometricAuthHelper.findFragmentActivity(activity))
        assertSame(activity, BiometricAuthHelper.findFragmentActivity(themed))
        assertSame(activity, BiometricAuthHelper.findFragmentActivity(doubleWrapped))
        assertNull(BiometricAuthHelper.findFragmentActivity(application))
        assertNull(BiometricAuthHelper.findFragmentActivity(null))
    }

    @Test
    fun testRequestBiometricUnlock_reportsErrorWhenNoActivityPresent() {
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)

        var succeeded = false
        var errorMsg: String? = null
        BiometricAuthHelper.requestBiometricUnlock(
            context = application,
            title = "Unlock",
            subtitle = "Subtitle",
            onSuccess = { succeeded = true },
            onError = { errorMsg = it },
        )

        assertFalse(succeeded)
        assertNotNull(errorMsg)
    }

    @Test
    fun testRequestBiometricUnlock_bypassesWhenNoLockEnrolledViaContextWrapper() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = false, canAuthenticate = false)
        val wrappedContext = ContextThemeWrapper(activity, android.R.style.Theme_Material_Light)

        var succeeded = false
        var errorMsg: String? = null
        BiometricAuthHelper.requestBiometricUnlock(
            context = wrappedContext,
            title = "Unlock",
            subtitle = "Subtitle",
            onSuccess = { succeeded = true },
            onError = { errorMsg = it },
        )

        assertTrue(succeeded)
        assertNull(errorMsg)
        assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))
    }

    @Test
    fun testRequestBiometricUnlock_failsClosedWithErrorWhenDeviceSecureButBiometricUnavailable() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = false)
        val wrappedContext = ContextThemeWrapper(activity, android.R.style.Theme_Material_Light)

        var succeeded = false
        var errorMsg: String? = null
        BiometricAuthHelper.requestBiometricUnlock(
            context = wrappedContext,
            title = "Unlock",
            subtitle = "Subtitle",
            onSuccess = { succeeded = true },
            onError = { errorMsg = it },
        )

        assertFalse(succeeded)
        assertNotNull(errorMsg)
        assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))
    }

    @Test
    fun testRequestBiometricUnlock_attachesPromptViaWrappedContextOnSecureDevice() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)
        val wrappedContext =
            ContextWrapper(ContextThemeWrapper(activity, android.R.style.Theme_Material_Light))

        var errorMsg: String? = null
        BiometricAuthHelper.requestBiometricUnlock(
            context = wrappedContext,
            title = "Unlock",
            subtitle = "Subtitle",
            onSuccess = {},
            onError = { errorMsg = it },
        )

        assertNull(errorMsg)
        assertNotNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))
    }

    @Test
    fun testEditCredential_eyeIconRevealsAndHidesSecretWhenLockDisabled() {
        val secret = "super-secret-token-123"
        val credential =
            Credential(
                id = "cred_1",
                name = "Test API Key",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to secret),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = false,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        val toggleNode = composeTestRule.onNodeWithContentDescription("Toggle Password Visibility")
        toggleNode.assertIsDisplayed()

        // Initially hidden: rendered layout text is masked, not the raw secret
        assertNotEquals(secret, renderedText("Token / API Key"))

        // Tap eye icon -> secret is revealed
        toggleNode.performClick()
        composeTestRule.waitForIdle()
        assertEquals(secret, renderedText("Token / API Key"))

        // Tap eye icon again -> secret is hidden again
        toggleNode.performClick()
        composeTestRule.waitForIdle()
        assertNotEquals(secret, renderedText("Token / API Key"))
    }

    @Test
    fun testEditCredential_eyeIconInsideDialogLaunchesBiometricPromptWhenLockEnabled() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)

        val secret = "locked-secret-456"
        val credential =
            Credential(
                id = "cred_2",
                name = "Locked API Key",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to secret),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = true,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))

        composeTestRule.onNodeWithContentDescription("Toggle Password Visibility").performClick()
        composeTestRule.waitForIdle()

        assertNull(ShadowToast.getTextOfLatestToast())
        val biometricFragment =
            activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment")
        assertNotNull(
            "Expected BiometricPrompt fragment to be attached from inside CredentialBottomSheet Dialog",
            biometricFragment,
        )
        // Secret remains hidden until authentication succeeds
        assertNotEquals(secret, renderedText("Token / API Key"))
        assertEquals(
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.from(activity).canAuthenticate(BiometricAuthHelper.allowedAuthenticators()),
        )
    }

    @Test
    fun testEditCredential_eyeIconUnlocksDirectlyWhenLockEnabledAndNoDeviceLockEnrolled() {
        configureBiometricEnvironment(isDeviceSecure = false, canAuthenticate = false)

        val secret = "bypass-secret-999"
        val credential =
            Credential(
                id = "cred_bypass",
                name = "No Lock Device Key",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to secret),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = true,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        assertNotEquals(secret, renderedText("Token / API Key"))

        composeTestRule.onNodeWithContentDescription("Toggle Password Visibility").performClick()
        composeTestRule.waitForIdle()

        assertEquals(secret, renderedText("Token / API Key"))
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testEditHeaderSetCredential_eyeIconLaunchesBiometricPromptWhenLockEnabled() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)

        val credential =
            Credential(
                id = "cred_3",
                name = "Custom Headers",
                type = CredentialType.HEADER_SET,
                dataMap = mapOf("headers" to "Authorization: Bearer secret-header-val"),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = true,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))

        val viewSecretNode = composeTestRule.onNodeWithContentDescription("View secret value")
        viewSecretNode.performScrollTo()
        val bounds = viewSecretNode.fetchSemanticsNode().boundsInRoot
        assertTrue("Expected positive bounds for eye icon, got $bounds", bounds.width > 0f && bounds.height > 0f)
        viewSecretNode.assertIsDisplayed()
        viewSecretNode.performClick()
        composeTestRule.waitForIdle()

        assertNull(ShadowToast.getTextOfLatestToast())
        composeTestRule.onNodeWithContentDescription("Hide secret value").assertDoesNotExist()
        val biometricFragment =
            activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment")
        assertNotNull(
            "Expected BiometricPrompt fragment to be attached for HEADER_SET eye icon",
            biometricFragment,
        )
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testEditHeaderSetCredential_eyeIconRevealsAndHidesWhenLockDisabled() {
        val credential =
            Credential(
                id = "cred_header_unlocked",
                name = "Unlocked Headers",
                type = CredentialType.HEADER_SET,
                dataMap = mapOf("headers" to "Authorization: Bearer secret-header-val"),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = false,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        val viewSecretNode = composeTestRule.onNodeWithContentDescription("View secret value")
        viewSecretNode.performScrollTo()
        viewSecretNode.assertIsDisplayed()
        viewSecretNode.performClick()
        composeTestRule.waitForIdle()

        val hideSecretNode = composeTestRule.onNodeWithContentDescription("Hide secret value")
        hideSecretNode.assertIsDisplayed()
        hideSecretNode.performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("View secret value").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testEditBasicAuthCredential_eyeIconRevealsAndHidesPasswordWhenLockDisabled() {
        val secretPassword = "my-basic-password-789"
        val credential =
            Credential(
                id = "cred_4",
                name = "Basic Auth Cred",
                type = CredentialType.BASIC_AUTH,
                dataMap = mapOf("username" to "alice", "password" to secretPassword),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = false,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        val toggleNode = composeTestRule.onNodeWithContentDescription("Toggle Password Visibility")
        toggleNode.performScrollTo()
        toggleNode.assertIsDisplayed()

        assertNotEquals(secretPassword, renderedText("Password"))

        toggleNode.performClick()
        composeTestRule.waitForIdle()
        assertEquals(secretPassword, renderedText("Password"))

        toggleNode.performClick()
        composeTestRule.waitForIdle()
        assertNotEquals(secretPassword, renderedText("Password"))
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testEditCredential_switchingTypeResetsRevealedPasswordVisibility() {
        val secret = "token-switch-secret"
        val credential =
            Credential(
                id = "cred_switch",
                name = "Switch Type Cred",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to secret),
            )

        composeTestRule.setContent {
            CredentialBottomSheet(
                credential = credential,
                isLockEnabled = false,
                onDismiss = {},
                onSave = {},
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Toggle Password Visibility").performClick()
        composeTestRule.waitForIdle()
        assertEquals(secret, renderedText("Token / API Key"))

        // Switch away to Basic Auth, then back to Bearer Token
        composeTestRule.onNodeWithText("Bearer Token (API Key)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Basic Auth (Username & Password)").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Basic Auth (Username & Password)").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Bearer Token (API Key)").performClick()
        composeTestRule.waitForIdle()

        assertNotEquals(secret, renderedText("Token / API Key"))
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testCredentialsScreen_editCredentialEyeIconLaunchesBiometricPrompt() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)
        val viewModel = MainViewModel(application)
        val credential =
            Credential(
                id = "cred_screen_edit_1",
                name = "Prod Bearer Credential",
                type = CredentialType.BEARER_TOKEN,
                dataMap = mapOf("token" to "prod-secret-token"),
            )
        runBlocking {
            viewModel.credentialRepository.saveCredential(credential)
        }
        viewModel.toggleLockCredentialsWithBiometric(true)

        try {
            composeTestRule.setContent {
                CredentialsScreen(viewModel = viewModel)
            }

            composeTestRule.waitUntil(timeoutMillis = 5000) {
                runCatching {
                    composeTestRule.onNodeWithText("Prod Bearer Credential").assertExists()
                    true
                }.getOrDefault(false)
            }

            composeTestRule.onNodeWithText("Prod Bearer Credential").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithText("Edit Credential").assertIsDisplayed()
            assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))

            composeTestRule.onNodeWithContentDescription("Toggle Password Visibility").performClick()
            composeTestRule.waitForIdle()

            assertNull(ShadowToast.getTextOfLatestToast())
            assertNotNull(
                activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"),
            )
            assertNotEquals("prod-secret-token", renderedText("Token / API Key"))
        } finally {
            viewModel.toggleLockCredentialsWithBiometric(false)
            runBlocking {
                viewModel.credentialRepository.deleteCredential(credential.id)
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testCredentialsScreen_enableBiometricBannerLaunchesBiometricPrompt() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)
        val viewModel = MainViewModel(application)
        viewModel.toggleLockCredentialsWithBiometric(false)

        composeTestRule.setContent {
            CredentialsScreen(viewModel = viewModel)
        }

        composeTestRule.waitForIdle()
        assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))

        composeTestRule.onNodeWithText("Enable Biometric Lock").performClick()
        composeTestRule.waitForIdle()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertNotNull(
            activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"),
        )
        assertFalse(viewModel.uiState.value.lockCredentialsWithBiometric)
    }

    @Test
    @Config(qualifiers = "w411dp-h915dp")
    fun testSettingsScreen_vaultSecurityLockSwitchLaunchesBiometricPrompt() {
        val activity = composeTestRule.activity
        configureBiometricEnvironment(isDeviceSecure = true, canAuthenticate = true)
        val viewModel = MainViewModel(application)
        viewModel.toggleLockCredentialsWithBiometric(false)

        composeTestRule.setContent {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = true,
                onRequestSmsPermission = {},
                hasNotificationPermission = true,
                onRequestNotificationPermission = {},
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule
            .onAllNodes(hasScrollToIndexAction())[0]
            .performScrollToNode(hasText("Lock Credentials with Device Lock"))
        composeTestRule.waitForIdle()
        assertNull(activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"))

        composeTestRule
            .onNode(
                isToggleable() and
                    hasAnySibling(hasText("Lock Credentials with Device Lock")),
            ).performClick()
        composeTestRule.waitForIdle()

        assertNull(ShadowToast.getTextOfLatestToast())
        assertNotNull(
            activity.supportFragmentManager.findFragmentByTag("androidx.biometric.BiometricFragment"),
        )
        assertFalse(viewModel.uiState.value.lockCredentialsWithBiometric)
    }
}
