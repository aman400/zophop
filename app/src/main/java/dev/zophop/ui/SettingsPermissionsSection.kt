package dev.zophop.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo
import dev.zophop.ui.workflows.WorkflowUiHelper

@Composable
fun SettingsPermissionsSection(
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    hasSendSmsPermission: Boolean,
    onRequestSendSmsPermission: () -> Unit,
    hasLocationPermission: Boolean,
    onRequestLocationPermission: () -> Unit,
    hasPhoneStatePermission: Boolean,
    onRequestPhoneStatePermission: () -> Unit,
    hasBluetoothPermission: Boolean,
    onRequestBluetoothPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    hasNotificationListenerPermission: Boolean,
    onRequestNotificationListenerPermission: () -> Unit,
    isBatteryOptimized: Boolean,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    discoveredPlugins: List<ExternalPluginServiceInfo>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
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
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "App Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Manage system permissions required by workflow triggers, condition checks, action delivery nodes, and external plugin modules.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "Built-in Node & System Permissions",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // SMS Receive Permission Row
            PermissionStatusRow(
                title = "Receive SMS",
                subtitle = "Required by SMS Trigger nodes to forward incoming text messages",
                isGranted = hasSmsPermission,
                onGrantClick = onRequestSmsPermission,
            )

            // SMS Send Permission Row
            PermissionStatusRow(
                title = "Send SMS",
                subtitle = "Required by Send SMS Action nodes to dispatch automated outgoing texts",
                isGranted = hasSendSmsPermission,
                onGrantClick = onRequestSendSmsPermission,
            )

            // Location & GPS Permission Row
            PermissionStatusRow(
                title = "Location & GPS",
                subtitle = "Required by GPS Check nodes to inspect coordinates and location services",
                isGranted = hasLocationPermission,
                onGrantClick = onRequestLocationPermission,
            )

            // Phone State & Telephony Permission Row
            PermissionStatusRow(
                title = "Phone State & SIM",
                subtitle = "Required by SIM Ready Check and Call Trigger nodes to inspect carrier and call state",
                isGranted = hasPhoneStatePermission,
                onGrantClick = onRequestPhoneStatePermission,
            )

            // Bluetooth Connect Permission Row (Android 12+ / API 31+)
            if (Build.VERSION.SDK_INT >= 31) {
                PermissionStatusRow(
                    title = "Bluetooth Devices",
                    subtitle = "Required by Bluetooth Check nodes to inspect connection and bonded devices",
                    isGranted = hasBluetoothPermission,
                    onGrantClick = onRequestBluetoothPermission,
                )
            }

            // Notification Permission Row
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionStatusRow(
                    title = "Show Notifications",
                    subtitle = "Required by Send Notification Action nodes and live workflow alerts",
                    isGranted = hasNotificationPermission,
                    onGrantClick = onRequestNotificationPermission,
                )
            }

            // Notification Listener Permission
            PermissionStatusRow(
                title = "Read App Notifications",
                subtitle = "Required by Notification Trigger and Parser nodes to intercept alerts",
                isGranted = hasNotificationListenerPermission,
                onGrantClick = onRequestNotificationListenerPermission,
            )

            // Battery Optimization Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keep App Running",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (!isBatteryOptimized) "Optimized (Best for reliability)" else "Warning (May delay forwarding messages)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!isBatteryOptimized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (isBatteryOptimized) {
                    OutlinedButton(onClick = onRequestIgnoreBatteryOptimizations) {
                        Text("Fix", fontSize = 12.sp, maxLines = 1, softWrap = false)
                    }
                }
            }

            // External Node Module Permissions Section (via Bridge)
            val externalPluginsWithPerms =
                remember(discoveredPlugins) {
                    discoveredPlugins.filter {
                        it.manifest.requiredPermissions.isNotEmpty() || it.manifest.setupActivity != null
                    }
                }

            if (externalPluginsWithPerms.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "External Plugin Permissions (IPC Bridge)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = "Permissions required by externally installed node modules. Tap 'Grant in Module' to let the external module handle the permission request natively via its setup bridge.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                externalPluginsWithPerms.forEach { plugin ->
                    val required = plugin.manifest.requiredPermissions
                    val allGranted =
                        if (required.isEmpty()) {
                            true
                        } else {
                            required.all { perm ->
                                context.packageManager.checkPermission(perm, plugin.packageName) == PackageManager.PERMISSION_GRANTED
                            }
                        }

                    val permSummary =
                        if (required.isEmpty()) {
                            "Configured via Module Setup"
                        } else {
                            required.joinToString { it.substringAfterLast('.') }
                        }

                    PermissionStatusRow(
                        title = plugin.manifest.displayName,
                        subtitle = "Module: ${plugin.packageName} • Requires: $permSummary",
                        isGranted = allGranted,
                        grantButtonLabel = if (plugin.manifest.setupActivity != null) "Grant in Module" else "Open App Info",
                        onGrantClick = {
                            if (plugin.manifest.setupActivity != null) {
                                try {
                                    val intent =
                                        Intent().apply {
                                            component = ComponentName(plugin.packageName, plugin.manifest.setupActivity!!)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            putExtra("source", "settings_permissions")
                                            putExtra("plugin_package", plugin.packageName)
                                        }
                                    context.startActivity(intent)
                                    return@PermissionStatusRow
                                } catch (_: Exception) {
                                }
                            }
                            try {
                                val intent =
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", plugin.packageName, null)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        WorkflowUiHelper.openAppSettings(context)
                    },
                ) {
                    Text("Open App System Settings", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun PermissionStatusRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    grantButtonLabel: String = "Grant",
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isGranted) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = "Granted",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        } else {
            Button(
                onClick = onGrantClick,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(
                    text = grantButtonLabel,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
