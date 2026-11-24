package dev.zophop.ui.workflows

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zophop.core.ui.components.dashedBorder
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.WorkflowNode

@Composable
fun WorkflowNodeCardItem(
    node: WorkflowNode,
    isFirst: Boolean,
    index: Int,
    totalNodes: Int,
    isUnsaved: Boolean = false,
    hasNotificationListenerPermission: Boolean = true,
    hasPostNotificationsPermission: Boolean = true,
    hasSmsPermission: Boolean = true,
    onToggleNode: (Boolean) -> Unit,
    onEditNode: () -> Unit,
    onDeleteNode: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val context = LocalContext.current

    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { dismissValue ->
                when (dismissValue) {
                    SwipeToDismissBoxValue.EndToStart -> {
                        if (!isFirst) onDeleteNode()
                        false
                    }
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onEditNode()
                        false
                    }
                    else -> false
                }
            },
        )

    var permissionCheckTrigger by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    permissionCheckTrigger++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var permissionInstructionData by remember { mutableStateOf<PermissionInstructionData?>(null) }
    val unifiedRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .UnifiedPluginRegistry(context)
        }
    val externalPluginRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .ExternalPluginRegistry(context)
        }
    var pluginManifest by remember { mutableStateOf<dev.zophop.plugin.api.NodePluginManifest?>(null) }
    var pluginInfo by remember { mutableStateOf<dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo?>(null) }

    LaunchedEffect(node.type) {
        pluginManifest = unifiedRegistry.getManifestByTypeId(node.type)
        val plugins = externalPluginRegistry.discoverPlugins()
        pluginInfo = plugins.find { it.manifest.typeId == node.type }
    }

    val multiplePermissionsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { grantMap ->
            permissionCheckTrigger++
            if (isLocationAndOtherPermsGranted(grantMap)) {
                onToggleNode(true)
            } else {
                val denied = grantMap.filter { !it.value }.keys.toList()
                permissionInstructionData =
                    WorkflowUiHelper.getPermissionInstruction(
                        context = context,
                        missingPermissions = denied,
                        nodeType = node.type,
                        pluginPackage = pluginInfo?.packageName,
                        setupActivity = pluginManifest?.setupActivity,
                    )
            }
        }

    val requestPermissionAction: () -> Unit = {
        val missing =
            WorkflowUiHelper.getMissingPermissions(
                context = context,
                nodeType = node.type,
                manifest = pluginManifest,
                pluginPackage = pluginInfo?.packageName,
            )
        val runtimePerms = missing.filter { it != "NOTIFICATION_LISTENER" }
        if (runtimePerms.isNotEmpty() && pluginInfo == null) {
            // Try to request using native android permission APIs first
            multiplePermissionsLauncher.launch(runtimePerms.toTypedArray())
        } else {
            // Special permission (e.g. notification listener) or external plugin setup
            permissionInstructionData =
                WorkflowUiHelper.getPermissionInstruction(
                    context = context,
                    missingPermissions = missing,
                    nodeType = node.type,
                    pluginPackage = pluginInfo?.packageName,
                    setupActivity = pluginManifest?.setupActivity,
                )
        }
    }

    val permissionSummary =
        remember(
            permissionCheckTrigger,
            node.type,
            pluginManifest,
            pluginInfo,
            hasNotificationListenerPermission,
            hasPostNotificationsPermission,
            hasSmsPermission,
        ) {
            WorkflowUiHelper.getMissingPermissionSummary(
                context = context,
                nodeType = node.type,
                manifest = pluginManifest,
                pluginPackage = pluginInfo?.packageName,
            )
        }

    val isConditional =
        remember(node.type, pluginManifest) {
            WorkflowUiHelper.isConditionalNode(node.type, pluginManifest)
        }

    val isPermissionMissing = permissionSummary != null
    val wasPermissionMissing = remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(isPermissionMissing) {
        if (isPermissionMissing) {
            if (node.isEnabled) {
                onToggleNode(false)
            }
        } else if (wasPermissionMissing.value == true) {
            onToggleNode(true)
        }
        wasPermissionMissing.value = isPermissionMissing
    }

    val cardBorder = Modifier.nodeCardBorder(isPermissionMissing, isUnsaved)

    val cardContent: @Composable () -> Unit = {
        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(cardBorder)
                    .clickable(onClick = onEditNode),
            shape = RoundedCornerShape(16.dp),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorkflowNodeTitleHeader(
                        nodeName = node.name,
                        nodeType = node.type,
                        isFirst = isFirst,
                        isUnsaved = isUnsaved,
                        isPermissionMissing = isPermissionMissing,
                        modifier = Modifier.weight(1f),
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (!isFirst) {
                            WorkflowNodeOptionsDropdown(
                                index = index,
                                totalNodes = totalNodes,
                                onMoveUp = onMoveUp,
                                onMoveDown = onMoveDown,
                                onDeleteNode = onDeleteNode,
                            )
                        }
                        Switch(
                            checked = node.isEnabled && !isPermissionMissing,
                            onCheckedChange = { checked ->
                                if (isPermissionMissing) {
                                    requestPermissionAction()
                                } else {
                                    onToggleNode(checked)
                                }
                            },
                            enabled = true,
                        )
                    }
                }

                // Sleek, compact permission alert banner on card
                if (permissionSummary != null) {
                    WorkflowNodePermissionBanner(
                        permissionSummary = permissionSummary,
                        onRequestPermission = requestPermissionAction,
                    )
                }

                // Preconditions badges (do not show on conditional nodes to prevent circular dependencies)
                if (!isConditional && node.preconditions.isNotEmpty()) {
                    WorkflowNodePreconditionsBadges(
                        preconditions = node.preconditions,
                    )
                }
            }
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            WorkflowNodeDismissBackground(
                dismissState = dismissState,
                isFirst = isFirst,
            )
        },
    ) {
        cardContent()
    }

    permissionInstructionData?.let { data ->
        WorkflowUiHelper.PermissionInstructionDialog(
            data = data,
            onDismiss = { permissionInstructionData = null },
        )
    }
}

@Composable
private fun WorkflowNodeOptionsDropdown(
    index: Int,
    totalNodes: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDeleteNode: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showMenu = true },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Move Up") },
                onClick = {
                    showMenu = false
                    onMoveUp()
                },
                enabled = index > 1,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Move Down") },
                onClick = {
                    showMenu = false
                    onMoveDown()
                },
                enabled = index < totalNodes - 1,
                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Delete Step") },
                onClick = {
                    showMenu = false
                    onDeleteNode()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

@Composable
private fun WorkflowNodePermissionBanner(
    permissionSummary: String,
    onRequestPermission: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onRequestPermission() },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = permissionSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onRequestPermission,
                modifier = Modifier.height(26.dp),
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    text = "Grant",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkflowNodePreconditionsBadges(
    preconditions: List<Precondition>,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        preconditions.forEach { prec ->
            val defaultIcon =
                when (prec) {
                    is Precondition.InternetCheck -> "🌐"
                    is Precondition.VpnCheck -> "🔐"
                    is Precondition.HealthCheck -> "🩺"
                    is Precondition.SimCheck -> "📱"
                    is Precondition.GpsCheck -> "📍"
                    is Precondition.BatteryCheck -> "🔋"
                    is Precondition.ChainCheck -> "⛓️"
                }
            val effectiveIcon = prec.icon.ifBlank { defaultIcon }
            val badgeLabel =
                when (prec) {
                    is Precondition.InternetCheck -> "Internet"
                    is Precondition.VpnCheck -> "VPN"
                    is Precondition.HealthCheck -> "Healthcheck"
                    is Precondition.SimCheck -> "SIM"
                    is Precondition.GpsCheck -> "GPS"
                    is Precondition.BatteryCheck -> "Battery (>= ${prec.minBatteryPercent}%)"
                    is Precondition.ChainCheck -> {
                        val rawName =
                            prec.name.ifBlank {
                                if (prec.nodes.size == 1) prec.nodes[0].name else "Chained"
                            }
                        rawName.removePrefix("⛓️").removePrefix("🔒").removePrefix("🛡️").trim()
                    }
                }
            val badgeText = "$effectiveIcon $badgeLabel" + if (!prec.isEnabled) " (Disabled)" else ""

            Surface(
                color = if (prec.isEnabled) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color =
                        if (prec.isEnabled) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowNodeDismissBackground(
    dismissState: SwipeToDismissBoxState,
    isFirst: Boolean,
) {
    val direction = dismissState.dismissDirection
    val color =
        when {
            direction == SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
            direction == SwipeToDismissBoxValue.EndToStart && !isFirst -> MaterialTheme.colorScheme.errorContainer
            else -> Color.Transparent
        }
    val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
    val icon = if (direction == SwipeToDismissBoxValue.StartToEnd) Icons.Default.Edit else Icons.Default.Delete
    val text = if (direction == SwipeToDismissBoxValue.StartToEnd) "Edit" else "Delete"
    val tint =
        if (direction == SwipeToDismissBoxValue.StartToEnd) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }

    if (direction != SwipeToDismissBoxValue.EndToStart || !isFirst) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(color, shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp),
            contentAlignment = alignment,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(imageVector = icon, contentDescription = text, tint = tint, modifier = Modifier.size(18.dp))
                Text(text = text, color = tint, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun WorkflowNodeTitleHeader(
    nodeName: String,
    nodeType: String,
    isFirst: Boolean,
    isUnsaved: Boolean,
    isPermissionMissing: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color =
                if (isPermissionMissing) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = WorkflowUiHelper.getNodeIcon(nodeType),
                    contentDescription = null,
                    tint = if (isPermissionMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = nodeName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isUnsaved) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "Unsaved",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                text = if (isFirst) "Fixed Trigger Entry Point" else WorkflowUiHelper.getNodeTitle(nodeType),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun isLocationAndOtherPermsGranted(grantMap: Map<String, Boolean>): Boolean {
    val locationSatisfied = (grantMap[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
        (grantMap[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    val otherPermsGranted = grantMap.filterKeys {
        it != Manifest.permission.ACCESS_FINE_LOCATION && it != Manifest.permission.ACCESS_COARSE_LOCATION
    }.values.all { it }
    return if (grantMap.containsKey(Manifest.permission.ACCESS_FINE_LOCATION) ||
        grantMap.containsKey(Manifest.permission.ACCESS_COARSE_LOCATION)
    ) {
        locationSatisfied && otherPermsGranted
    } else {
        grantMap.isNotEmpty() && grantMap.values.all { it }
    }
}

@Composable
private fun Modifier.nodeCardBorder(isPermissionMissing: Boolean, isUnsaved: Boolean): Modifier =
    when {
        isPermissionMissing -> border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
        isUnsaved -> dashedBorder(Color(0xFFFFD54F), 2.dp, 16.dp)
        else -> this
    }
