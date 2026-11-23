package dev.zophop.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private var hasSmsPermission by mutableStateOf(false)
    private var hasNotificationPermission by mutableStateOf(false)

    private val smsPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasSmsPermission = isGranted
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasNotificationPermission = isGranted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Apply FLAG_SECURE only in release builds to allow screen inspection during debug
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebug) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        checkPermissions()
        handleIntent(intent)

        setContent {
            ZopHopTheme {
                Surface {
                    MainAppScreen(
                        viewModel = viewModel,
                        hasSmsPermission = hasSmsPermission,
                        onRequestSmsPermission = {
                            smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
                        },
                        hasNotificationPermission = hasNotificationPermission,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        viewModel.checkPermissionsAndStatus()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val workflowId = intent?.getStringExtra("open_workflow_id")
        if (!workflowId.isNullOrBlank()) {
            viewModel.openWorkflowLogsById(workflowId)
        }
    }

    private fun checkPermissions() {
        hasSmsPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED

        hasNotificationPermission =
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
    }
}
