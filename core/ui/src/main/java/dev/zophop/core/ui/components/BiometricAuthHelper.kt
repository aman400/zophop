package dev.zophop.core.ui.components

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager

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
}
