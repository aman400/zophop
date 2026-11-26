package dev.zophop.core.ui.components

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {
    fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    fun canBypassWhenNoLockEnrolled(
        isDeviceSecure: Boolean?,
        canAuthenticateResult: Int,
    ): Boolean =
        isDeviceSecure == false &&
            (
                canAuthenticateResult == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ||
                    canAuthenticateResult == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
            )

    fun canBypassWhenNoLockEnrolled(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val canAuthenticate = BiometricManager.from(context).canAuthenticate(allowedAuthenticators())
        return canBypassWhenNoLockEnrolled(
            isDeviceSecure = keyguardManager?.isDeviceSecure,
            canAuthenticateResult = canAuthenticate,
        )
    }

    tailrec fun findFragmentActivity(context: Context?): FragmentActivity? =
        when (context) {
            is FragmentActivity -> context
            is ContextWrapper -> findFragmentActivity(context.baseContext)
            else -> null
        }

    fun requestBiometricUnlock(
        context: Context,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
    ) {
        val authenticators = allowedAuthenticators()
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(authenticators)
        val activity = findFragmentActivity(context)

        when {
            canBypassWhenNoLockEnrolled(
                isDeviceSecure = keyguardManager?.isDeviceSecure,
                canAuthenticateResult = canAuthenticate,
            ) -> onSuccess()
            activity == null ->
                onError("Device authentication required: FragmentActivity unavailable")
            canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS ->
                onError("Device authentication unavailable (error code: $canAuthenticate)")
            else ->
                try {
                    val executor = ContextCompat.getMainExecutor(activity)
                    val prompt =
                        BiometricPrompt(
                            activity,
                            executor,
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    super.onAuthenticationSucceeded(result)
                                    onSuccess()
                                }

                                override fun onAuthenticationError(
                                    errorCode: Int,
                                    errString: CharSequence,
                                ) {
                                    super.onAuthenticationError(errorCode, errString)
                                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                    ) {
                                        onError(errString.toString())
                                    }
                                }
                            },
                        )
                    val promptInfo =
                        BiometricPrompt.PromptInfo
                            .Builder()
                            .setTitle(title)
                            .setSubtitle(subtitle)
                            .setAllowedAuthenticators(authenticators)
                            .build()

                    prompt.authenticate(promptInfo)
                } catch (e: SecurityException) {
                    onError(e.message ?: "Authentication failed")
                } catch (e: IllegalArgumentException) {
                    onError(e.message ?: "Authentication failed")
                } catch (e: IllegalStateException) {
                    onError(e.message ?: "Authentication failed")
                }
        }
    }
}
