package dev.zophop.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zophop.BuildConfig
import dev.zophop.core.ui.AppThemeMode
import dev.zophop.core.ui.AppThemePalette
import dev.zophop.core.ui.components.BiometricAuthHelper
import dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo
import dev.zophop.feature.backup.ExportBackupDialog
import dev.zophop.feature.backup.ImportBackupDialog
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val discoveredPlugins by viewModel.discoveredPlugins.collectAsState()
    val context = LocalContext.current

    var pendingEnableWorkflowNotifications by remember { mutableStateOf(false) }
    var showNotificationPermissionRationaleDialog by remember { mutableStateOf(false) }
    var showExportBackupDialog by remember { mutableStateOf(false) }
    var showImportBackupDialog by remember { mutableStateOf(false) }
    val uiPrefs = remember(context) { context.getSharedPreferences("zophop_ui_prefs", Context.MODE_PRIVATE) }

    LaunchedEffect(hasNotificationPermission) {
        if (hasNotificationPermission) {
            uiPrefs.edit { putBoolean("notif_permission_requested", false) }
            if (pendingEnableWorkflowNotifications) {
                pendingEnableWorkflowNotifications = false
                viewModel.toggleShowWorkflowNotifications(true)
            }
        }
    }

    val workflows by viewModel.workflows.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    val preconditions by viewModel.preconditions.collectAsState()

    val permissionsState = rememberSettingsPermissions(context)

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    viewModel.checkPermissionsAndStatus()
                    permissionsState.refresh()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun authenticateSecurityToggle(targetState: Boolean) {
        if (BiometricAuthHelper.canBypassWhenNoLockEnrolled(context)) {
            viewModel.toggleLockCredentialsWithBiometric(targetState)
            return
        }
        val authenticators = BiometricAuthHelper.allowedAuthenticators()
        val biometricManager = BiometricManager.from(context)
        val activity = context as? FragmentActivity
        if (activity != null && biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt =
                BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            viewModel.toggleLockCredentialsWithBiometric(targetState)
                        }
                    },
                )
            val promptInfo =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Vault Security Lock")
                    .setSubtitle("Authenticate using biometrics or device lock to change vault security settings.")
                    .setAllowedAuthenticators(authenticators)
                    .build()

            prompt.authenticate(promptInfo)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            item {
                GlobalWorkflowsSwitchCard(
                    checked = uiState.globalWorkflowsEnabled,
                    onCheckedChange = { viewModel.toggleGlobalWorkflowsEnabled(it) },
                )
            }

            item {
                val activity = LocalActivity.current
                WorkflowExecutionAlertsCard(
                    checked = uiState.showWorkflowNotifications,
                    hasNotificationPermission = hasNotificationPermission,
                    onCheckedChange = { enable ->
                        pendingEnableWorkflowNotifications = false
                        viewModel.toggleShowWorkflowNotifications(enable)
                    },
                    onRequestEnableWithoutPermission = {
                        pendingEnableWorkflowNotifications = true
                        val shouldShowRationale = activity?.shouldShowRequestPermissionRationale(
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) == true
                        val hasRequestedBefore = uiPrefs.getBoolean("notif_permission_requested", false)
                        if (shouldShowRationale || hasRequestedBefore) {
                            showNotificationPermissionRationaleDialog = true
                        } else {
                            uiPrefs.edit { putBoolean("notif_permission_requested", true) }
                            onRequestNotificationPermission()
                        }
                    },
                )
            }

            item {
                SettingsThemeSection(
                    selectedMode = AppThemeMode.fromId(uiState.themeMode),
                    onSelectMode = { mode -> viewModel.updateThemeMode(mode.id) },
                    selectedPalette = AppThemePalette.fromId(uiState.themePalette),
                    onSelectPalette = { palette -> viewModel.updateThemePalette(palette.id) },
                )
            }

            item {
                VaultSecurityLockCard(
                    checked = uiState.lockCredentialsWithBiometric,
                    onCheckedChange = { authenticateSecurityToggle(it) },
                )
            }

            item {
                ConcurrencyAndQueueCard(
                    maxParallel = uiState.maxParallelExecutions,
                    onMaxParallelChange = { viewModel.updateMaxParallelExecutions(it) },
                    maxQueue = uiState.maxExecutionQueueSize,
                    onMaxQueueChange = { viewModel.updateMaxExecutionQueueSize(it) },
                    queueTimeoutMinutes = uiState.expiryMinutes.toIntOrNull() ?: 10,
                    onQueueTimeoutMinutesChange = { viewModel.updateExpiryMinutes(it.toString()) },
                )
            }

            // System Permissions & Optimization Section
            item {
                SettingsPermissionsSection(
                    hasSmsPermission = hasSmsPermission,
                    onRequestSmsPermission = onRequestSmsPermission,
                    hasSendSmsPermission = permissionsState.hasSendSmsPermission,
                    onRequestSendSmsPermission = permissionsState.onRequestSendSmsPermission,
                    hasLocationPermission = permissionsState.hasLocationPermission,
                    onRequestLocationPermission = permissionsState.onRequestLocationPermission,
                    hasPhoneStatePermission = permissionsState.hasPhoneStatePermission,
                    onRequestPhoneStatePermission = permissionsState.onRequestPhoneStatePermission,
                    hasBluetoothPermission = permissionsState.hasBluetoothPermission,
                    onRequestBluetoothPermission = permissionsState.onRequestBluetoothPermission,
                    hasNotificationPermission = hasNotificationPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    hasNotificationListenerPermission = uiState.hasNotificationListenerPermission,
                    onRequestNotificationListenerPermission = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (_: Exception) {
                        }
                    },
                    isBatteryOptimized = uiState.isBatteryOptimized,
                    onRequestIgnoreBatteryOptimizations = {
                        try {
                            val intent =
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = "package:${context.packageName}".toUri()
                                }
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    },
                    discoveredPlugins = discoveredPlugins,
                )
            }

            item {
                BackupAndRestoreCard(
                    onExport = { showExportBackupDialog = true },
                    onImport = { showImportBackupDialog = true },
                )
            }

            item {
                InstalledNodePluginsCard(discoveredPlugins = discoveredPlugins)
            }

            item {
                AboutAppCard(
                    onVisitWebsite = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, "https://aman400.github.io/zophop".toUri())
                            context.startActivity(intent)
                        } catch (_: Exception) {
                        }
                    },
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (showExportBackupDialog) {
        ExportBackupDialog(
            backupService = viewModel.backupService,
            isBiometricLockEnabled = uiState.lockCredentialsWithBiometric,
            workflowsCount = workflows.size,
            credentialsCount = credentials.size,
            preconditionsCount = preconditions.size,
            onDismiss = { showExportBackupDialog = false },
        )
    }

    if (showImportBackupDialog) {
        ImportBackupDialog(
            backupService = viewModel.backupService,
            isBiometricLockEnabled = uiState.lockCredentialsWithBiometric,
            onDismiss = { showImportBackupDialog = false },
        )
    }

    if (showNotificationPermissionRationaleDialog) {
        NotificationPermissionRationaleDialog(
            onDismiss = {
                showNotificationPermissionRationaleDialog = false
                pendingEnableWorkflowNotifications = false
            },
            onOpenSettings = {
                showNotificationPermissionRationaleDialog = false
                try {
                    val intent =
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val intent =
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                    }
                }
            },
        )
    }
}

@Composable
private fun BackupAndRestoreCard(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Backup & Data Management",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Export workflows, credentials (optionally protected with a PIN/password), and preconditions to a portable JSON backup file, or restore from an existing backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Export", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onImport,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun GlobalWorkflowsSwitchCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Global Automations Switch",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Global Workflows Engine Enabled",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Master toggle to pause or resume all workflow processing. Individual workflow states are preserved across toggles and can also be controlled from the Quick Settings Tile.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun WorkflowExecutionAlertsCard(
    checked: Boolean,
    hasNotificationPermission: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onRequestEnableWithoutPermission: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Workflow Execution Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Live Execution Notifications",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Display ongoing status notifications when background workflows execute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    modifier = Modifier.testTag("workflow_execution_alerts_switch"),
                    checked = checked,
                    onCheckedChange = { enable ->
                        val notifGranted = Build.VERSION.SDK_INT < 33 || hasNotificationPermission
                        if (enable && !notifGranted) {
                            onRequestEnableWithoutPermission()
                        } else {
                            onCheckedChange(enable)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun VaultSecurityLockCard(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Vault Security & Lock",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Lock Credentials with Device Lock",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Requires Biometrics or Device Credentials (PIN, Pattern, Password) to view secret passwords in Vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        }
    }
}

@Composable
private fun ConcurrencyAndQueueCard(
    maxParallel: Int,
    onMaxParallelChange: (Int) -> Unit,
    maxQueue: Int,
    onMaxQueueChange: (Int) -> Unit,
    queueTimeoutMinutes: Int,
    onQueueTimeoutMinutesChange: (Int) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Concurrency & Execution Queue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Max Parallel Executions",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Limit concurrent workflow executions (Default: 3)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsValueBadge(text = "$maxParallel Active")
                }
                val maxParallelState =
                    rememberSliderState(
                        value = maxParallel.toFloat(),
                        steps = 8,
                        trackRange = 1f..10f,
                    )
                LaunchedEffect(maxParallel) {
                    if (maxParallelState.value != maxParallel.toFloat()) {
                        maxParallelState.value = maxParallel.toFloat()
                    }
                }
                Slider(
                    state = maxParallelState,
                    onValueChange = {
                        maxParallelState.value = it
                        onMaxParallelChange(it.roundToInt())
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Max Queue Size",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Maximum background executions queued (Default: 20)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsValueBadge(text = "Max $maxQueue")
                }
                val maxQueueState =
                    rememberSliderState(
                        value = maxQueue.toFloat(),
                        steps = 18,
                        trackRange = 5f..100f,
                    )
                LaunchedEffect(maxQueue) {
                    if (maxQueueState.value != maxQueue.toFloat()) {
                        maxQueueState.value = maxQueue.toFloat()
                    }
                }
                Slider(
                    state = maxQueueState,
                    onValueChange = {
                        maxQueueState.value = it
                        onMaxQueueChange(it.roundToInt())
                    },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Queue Expiry Timeout",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Duration queued executions wait for preconditions before expiring",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SettingsValueBadge(text = "${queueTimeoutMinutes}m Timeout")
                }
                val queueTimeoutState =
                    rememberSliderState(
                        value = queueTimeoutMinutes.toFloat(),
                        steps = 58,
                        trackRange = 1f..60f,
                    )
                LaunchedEffect(queueTimeoutMinutes) {
                    if (queueTimeoutState.value != queueTimeoutMinutes.toFloat()) {
                        queueTimeoutState.value = queueTimeoutMinutes.toFloat()
                    }
                }
                Slider(
                    state = queueTimeoutState,
                    onValueChange = {
                        queueTimeoutState.value = it
                        onQueueTimeoutMinutesChange(it.roundToInt())
                    },
                )
            }
        }
    }
}

@Composable
private fun InstalledNodePluginsCard(
    discoveredPlugins: List<ExternalPluginServiceInfo>,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "🧩 Installed Node Plugins (${discoveredPlugins.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Discovers third-party plugin apps installed on this device that export custom workflow pipeline step nodes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (discoveredPlugins.isEmpty()) {
                Text(
                    text = "No external plugin apps detected. Install a ZopHop plugin (e.g. Telegram Plugin) to see it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                discoveredPlugins.forEach { plugin ->
                    PluginItemView(plugin)
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = "Open Node Plugin Ecosystem",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "Developers can build and publish custom nodes as standalone Android apps using the ZopHop Plugin API.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsValueBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
internal fun PluginItemView(plugin: ExternalPluginServiceInfo) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = plugin.manifest.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "v${plugin.apiVersion}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = plugin.manifest.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Package: ${plugin.packageName}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun AboutAppCard(
    onVisitWebsite: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape),
                ) {
                    androidx.compose.foundation.Image(
                        painter =
                            androidx.compose.ui.res
                                .painterResource(id = dev.zophop.R.drawable.ic_launcher_background),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    androidx.compose.foundation.Image(
                        painter =
                            androidx.compose.ui.res
                                .painterResource(id = dev.zophop.R.drawable.ic_launcher_foreground),
                        contentDescription = "App Icon",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "About ZopHop",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "Automatically forward SMS, notifications, and OTPs to your devices securely.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onVisitWebsite,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Visit Official Website & Docs")
            }
        }
    }
}

@Composable
private fun NotificationPermissionRationaleDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notification Permission Required") },
        text = {
            Text(
                "To display live execution alerts when background workflows run, please enable notifications and notification listener access in the app's settings.",
            )
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private class SettingsPermissionsState(
    val hasLocationPermission: Boolean,
    val hasPhoneStatePermission: Boolean,
    val hasSendSmsPermission: Boolean,
    val hasBluetoothPermission: Boolean,
    val onRequestLocationPermission: () -> Unit,
    val onRequestPhoneStatePermission: () -> Unit,
    val onRequestSendSmsPermission: () -> Unit,
    val onRequestBluetoothPermission: () -> Unit,
    val refresh: () -> Unit,
)

@Composable
private fun rememberSettingsPermissions(context: Context): SettingsPermissionsState {
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    var hasPhoneStatePermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
        )
    }

    var hasSendSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED,
        )
    }

    var hasBluetoothPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 31) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            },
        )
    }

    fun refreshLocalPermissions() {
        hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasPhoneStatePermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        hasSendSmsPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        hasBluetoothPermission =
            if (Build.VERSION.SDK_INT >= 31) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            refreshLocalPermissions()
        }

    val phoneStatePermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            refreshLocalPermissions()
        }

    val sendSmsPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            refreshLocalPermissions()
        }

    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) {
            refreshLocalPermissions()
        }

    return remember(
        hasLocationPermission,
        hasPhoneStatePermission,
        hasSendSmsPermission,
        hasBluetoothPermission,
    ) {
        SettingsPermissionsState(
            hasLocationPermission = hasLocationPermission,
            hasPhoneStatePermission = hasPhoneStatePermission,
            hasSendSmsPermission = hasSendSmsPermission,
            hasBluetoothPermission = hasBluetoothPermission,
            onRequestLocationPermission = {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            onRequestPhoneStatePermission = {
                phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            },
            onRequestSendSmsPermission = {
                sendSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
            },
            onRequestBluetoothPermission = {
                if (Build.VERSION.SDK_INT >= 31) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            },
            refresh = ::refreshLocalPermissions,
        )
    }
}
