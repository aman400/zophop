package dev.zophop.ui

import android.Manifest
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.zophop.core.ui.AppThemeMode
import dev.zophop.core.ui.AppThemePalette
import dev.zophop.core.ui.resolveDarkTheme
import dev.zophop.service.SmsForwarderService

@AndroidEntryPoint
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
            val uiState by viewModel.uiState.collectAsState()
            val themeMode = AppThemeMode.fromId(uiState.themeMode)
            val themePalette = AppThemePalette.fromId(uiState.themePalette)
            val isDark = resolveDarkTheme(themeMode)
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = !isDark
                    }
                }
            }

            ZopHopTheme(
                themeMode = themeMode,
                themePalette = themePalette,
            ) {
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

    override fun onStart() {
        super.onStart()
        if (viewModel.uiState.value.isEnabled) {
            SmsForwarderService.startService(this)
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        viewModel.checkPermissionsAndStatus()
        viewModel.discoverInstalledPlugins()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val workflowId = intent?.getStringExtra("open_workflow_id")
        val notificationId = intent?.getIntExtra("notification_id", -1) ?: -1
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationId != -1) {
            nm?.cancel(notificationId)
        }
        if (!workflowId.isNullOrBlank()) {
            nm?.cancel(workflowId.hashCode())
            val showActive = intent.getBooleanExtra("show_active_execution", false)
            if (showActive) {
                viewModel.navigateToExecutingWorkflow(workflowId)
            } else {
                viewModel.openWorkflowLogsById(workflowId)
            }
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
