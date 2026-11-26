package dev.zophop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.CancelExecutionConfirmDialog
import dev.zophop.core.ui.components.ZopHopSpriteBackdrop
import dev.zophop.data.MessageEntity
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.feature.backup.ExportBackupDialog
import dev.zophop.feature.backup.ImportBackupDialog
import dev.zophop.ui.credentials.CredentialsScreen
import dev.zophop.ui.preconditions.PreconditionEditorScreen
import dev.zophop.ui.preconditions.PreconditionsScreen
import dev.zophop.ui.workflows.TestRunLogBottomSheet
import dev.zophop.ui.workflows.WorkflowEditorScreen
import dev.zophop.ui.workflows.WorkflowLogsBottomSheet
import dev.zophop.ui.workflows.WorkflowsListScreen

enum class AppTab {
    WORKFLOWS,
    PRECONDITIONS,
    CREDENTIALS,
    SETTINGS,
}

sealed class MainScreenDestination {
    object MainTabs : MainScreenDestination()

    data class EditWorkflow(
        val workflow: Workflow,
    ) : MainScreenDestination()

    data class ViewLogs(
        val workflow: Workflow,
        val initialTab: Int = 0,
    ) : MainScreenDestination()

    data class EditPrecondition(
        val precondition: PreconditionEntity?,
    ) : MainScreenDestination()
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(AppTab.WORKFLOWS) }
    var activeEditingWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var activeLogsWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var activeLogsInitialTab by remember { mutableIntStateOf(0) }
    var activeEditingPrecondition by remember { mutableStateOf<PreconditionEntity?>(null) }
    var showAddPreconditionScreen by remember { mutableStateOf(false) }
    var activeCreatePreconditionCallback by remember { mutableStateOf<((PreconditionEntity) -> Unit)?>(null) }

    val uiState by viewModel.uiState.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    val preconditions by viewModel.preconditions.collectAsState()
    val activeExecutionState by viewModel.activeExecutionState.collectAsState()
    val targetExecutingWorkflowId by viewModel.targetExecutingWorkflowId.collectAsState()
    val queuedMessages by viewModel.queuedMessages.collectAsState()
    val queuedWorkflowLogs by viewModel.queuedWorkflowLogs.collectAsState()
    val totalQueuedCount = queuedMessages.size + queuedWorkflowLogs.size
    val queuedWorkflowIds = remember(queuedWorkflowLogs) { queuedWorkflowLogs.map { it.workflowId }.toSet() }
    var showActiveExecutionSheet by remember { mutableStateOf(false) }
    var showExportBackupDialog by remember { mutableStateOf(false) }
    var showImportBackupDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(uiState.selectedWorkflowForLogs) {
        if (uiState.selectedWorkflowForLogs != null) {
            activeLogsInitialTab = 0
            activeLogsWorkflow = uiState.selectedWorkflowForLogs
            viewModel.clearSelectedWorkflowForLogs()
        }
    }

    androidx.compose.runtime.LaunchedEffect(targetExecutingWorkflowId) {
        if (!targetExecutingWorkflowId.isNullOrBlank()) {
            activeEditingWorkflow = null
            activeLogsWorkflow = null
            showAddPreconditionScreen = false
            activeEditingPrecondition = null
            selectedTab = AppTab.WORKFLOWS
            showActiveExecutionSheet = true
            viewModel.clearTargetExecutingWorkflowId()
        }
    }

    val currentDestination: MainScreenDestination =
        remember(
            activeEditingWorkflow,
            activeLogsWorkflow,
            showAddPreconditionScreen,
            activeEditingPrecondition,
        ) {
            when {
                activeEditingWorkflow != null -> MainScreenDestination.EditWorkflow(activeEditingWorkflow!!)
                activeLogsWorkflow != null -> MainScreenDestination.ViewLogs(activeLogsWorkflow!!, activeLogsInitialTab)
                showAddPreconditionScreen || activeEditingPrecondition != null ->
                    MainScreenDestination.EditPrecondition(
                        activeEditingPrecondition,
                    )
                else -> MainScreenDestination.MainTabs
            }
        }

    // Only intercept back navigation at root when on a secondary tab
    BackHandler(enabled = currentDestination is MainScreenDestination.MainTabs && selectedTab != AppTab.WORKFLOWS) {
        selectedTab = AppTab.WORKFLOWS
    }

    AnimatedContent(
        targetState = currentDestination,
        transitionSpec = {
            if (targetState is MainScreenDestination.MainTabs) {
                // Popping back to tabs: Slide out to right
                (fadeIn(animationSpec = tween(220)) + slideInHorizontally(animationSpec = tween(220)) { -it / 3 }) togetherWith
                    (fadeOut(animationSpec = tween(180)) + slideOutHorizontally(animationSpec = tween(180)) { it })
            } else {
                // Pushing new screen: Slide in from right
                (fadeIn(animationSpec = tween(220)) + slideInHorizontally(animationSpec = tween(220)) { it }) togetherWith
                    (fadeOut(animationSpec = tween(180)) + slideOutHorizontally(animationSpec = tween(180)) { -it / 3 })
            }
        },
        label = "ScreenDestinationTransition",
    ) { destination ->
        when (destination) {
            is MainScreenDestination.EditWorkflow -> {
                WorkflowEditorScreen(
                    workflow = destination.workflow,
                    credentials = credentials,
                    vaultPreconditions = preconditions,
                    hasNotificationListenerPermission = uiState.hasNotificationListenerPermission,
                    hasPostNotificationsPermission = hasNotificationPermission,
                    hasSmsPermission = hasSmsPermission,
                    onBack = { activeEditingWorkflow = null },
                    onSaveWorkflow = { updated ->
                        viewModel.saveWorkflow(updated)
                        activeEditingWorkflow = null
                    },
                    onTestRunWorkflow = { wf, callback ->
                        viewModel.testRunWorkflow(wf, callback)
                    },
                    onTestRunWorkflowWithProgress = { wf, onProgress, onResult ->
                        viewModel.testRunWorkflowWithProgress(wf, onProgress, onResult)
                    },
                    onCancelWorkflowExecution = { id ->
                        viewModel.cancelWorkflowExecution(id)
                    },
                    onSaveCredential = { newCred ->
                        viewModel.saveCredential(newCred)
                    },
                    onRequestCreatePrecondition = { onCreated ->
                        activeCreatePreconditionCallback = onCreated
                    },
                )
            }
            is MainScreenDestination.ViewLogs -> {
                WorkflowLogsBottomSheet(
                    workflow = destination.workflow,
                    logsFlow = viewModel.getLogsForWorkflow(destination.workflow.id),
                    onClearLogs = { viewModel.clearLogsForWorkflow(destination.workflow.id) },
                    onDeleteExecutionLog = { id -> viewModel.deleteExecutionLog(id) },
                    onRetryWorkflowFromFailedNode = { wf, failedNodeId, originalLogId, onProgress, onResult ->
                        viewModel.retryWorkflowFromFailedNode(wf, failedNodeId, originalLogId, onProgress, onResult)
                    },
                    queuedMessages = queuedMessages,
                    onRetryQueuedWorkflow = { log -> viewModel.retryQueuedWorkflow(log) },
                    onCancelQueuedWorkflowLog = { id -> viewModel.cancelQueuedWorkflowExecution(id) },
                    onCancelQueuedMessage = { id -> viewModel.deleteQueuedMessage(id) },
                    onProcessQueueNow = { viewModel.processQueueNow() },
                    initialTab = destination.initialTab,
                    onDismiss = { activeLogsWorkflow = null },
                )
            }
            is MainScreenDestination.EditPrecondition -> {
                PreconditionEditorScreen(
                    precondition = destination.precondition,
                    credentials = credentials,
                    onTestPreconditionWithProgress = { entity, onProgress, onResult ->
                        viewModel.testPreconditionWithProgress(entity, onProgress, onResult)
                    },
                    onCancelTestPrecondition = { id ->
                        viewModel.cancelPreconditionTest(id)
                    },
                    onDismiss = {
                        showAddPreconditionScreen = false
                        activeEditingPrecondition = null
                    },
                    onSave = { entity ->
                        viewModel.savePrecondition(entity)
                        showAddPreconditionScreen = false
                        activeEditingPrecondition = null
                    },
                )
            }
            is MainScreenDestination.MainTabs -> {
                ZopHopSpriteBackdrop(seedKey = "tab_${selectedTab.name}") {
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = {
                            MainTopAppBar(
                                selectedTab = selectedTab,
                                workflows = workflows,
                                preconditions = preconditions,
                                credentials = credentials,
                                isEngineEnabled = uiState.isEnabled,
                                queuedCount = totalQueuedCount,
                                onExportBackup = { showExportBackupDialog = true },
                                onImportBackup = { showImportBackupDialog = true },
                            )
                        },
                        bottomBar = {
                            MainBottomNavigationBar(
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it },
                            )
                        },
                    ) { innerPadding ->
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                        ) {
                            activeExecutionState?.let { activeState ->
                                ActiveExecutionBanner(
                                    activeState = activeState,
                                    onViewSheet = { showActiveExecutionSheet = true },
                                    onCancelExecution = { viewModel.cancelWorkflowExecution(activeState.workflowId) },
                                )
                            }

                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                            ) {
                                MainTabContent(
                                    selectedTab = selectedTab,
                                    viewModel = viewModel,
                                    workflows = workflows,
                                    preconditions = preconditions,
                                    credentials = credentials,
                                    queuedMessages = queuedMessages,
                                    queuedWorkflowIds = queuedWorkflowIds,
                                    hasSmsPermission = hasSmsPermission,
                                    onRequestSmsPermission = onRequestSmsPermission,
                                    hasNotificationPermission = hasNotificationPermission,
                                    onRequestNotificationPermission = onRequestNotificationPermission,
                                    onEditWorkflow = { activeEditingWorkflow = it },
                                    onViewLogs = {
                                        activeLogsInitialTab = 0
                                        activeLogsWorkflow = it
                                    },
                                    onViewQueue = {
                                        activeLogsInitialTab = 1
                                        activeLogsWorkflow = it
                                    },
                                    onEditPrecondition = { activeEditingPrecondition = it },
                                    onProcessQueueNow = { viewModel.processQueueNow() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showActiveExecutionSheet && activeExecutionState != null) {
        val activeState = activeExecutionState!!
        val wf = workflows.find { it.id == activeState.workflowId }
        TestRunLogBottomSheet(
            workflowName = wf?.name ?: activeState.workflowName,
            steps = activeState.steps,
            isTesting = activeState.isExecuting,
            onCancelExecution = { viewModel.cancelWorkflowExecution(activeState.workflowId) },
            onDismiss = { showActiveExecutionSheet = false },
        )
    }

    if (activeCreatePreconditionCallback != null) {
        PreconditionEditorScreen(
            precondition = null,
            credentials = credentials,
            onTestPreconditionWithProgress = { entity, onProgress, onResult ->
                viewModel.testPreconditionWithProgress(entity, onProgress, onResult)
            },
            onCancelTestPrecondition = { id ->
                viewModel.cancelPreconditionTest(id)
            },
            onDismiss = { activeCreatePreconditionCallback = null },
            onSave = { newPrec ->
                viewModel.savePrecondition(newPrec)
                activeCreatePreconditionCallback?.invoke(newPrec)
                activeCreatePreconditionCallback = null
            },
        )
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopAppBar(
    selectedTab: AppTab,
    workflows: List<Workflow>,
    preconditions: List<PreconditionEntity>,
    credentials: List<Credential>,
    isEngineEnabled: Boolean,
    queuedCount: Int = 0,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(
                    text =
                        when (selectedTab) {
                            AppTab.WORKFLOWS -> "Workflows"
                            AppTab.PRECONDITIONS -> "Preconditions"
                            AppTab.CREDENTIALS -> "Credentials"
                            AppTab.SETTINGS -> "Settings & Permissions"
                        },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                when (selectedTab) {
                    AppTab.WORKFLOWS -> {
                        val autoActiveCount =
                            workflows.count {
                                it.isEnabled && it.nodes.firstOrNull()?.type != NodeType.MANUAL_TRIGGER
                            }
                        val manualCount = workflows.count { it.nodes.firstOrNull()?.type == NodeType.MANUAL_TRIGGER }
                        val queuedSuffix = if (queuedCount > 0) " • ⏳ $queuedCount Queued" else ""
                        Text(
                            text = "$autoActiveCount Auto Active • $manualCount Manual$queuedSuffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppTab.PRECONDITIONS -> {
                        val activeCount = preconditions.count { it.isEnabled }
                        Text(
                            text = "$activeCount Active • ${if (isEngineEnabled) "Engine enabled" else "Engine disabled"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AppTab.CREDENTIALS -> {
                        Text(
                            text = "${credentials.size} Credentials Stored",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {}
                }
            }
        },
        actions = {
            val chipText =
                when (selectedTab) {
                    AppTab.WORKFLOWS -> "${workflows.size} Workflows"
                    AppTab.PRECONDITIONS -> "${preconditions.size} Rules"
                    AppTab.CREDENTIALS -> "${credentials.size} Keys"
                    else -> null
                }
            if (chipText != null) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = chipText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    colors =
                        SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Backup and Data Options",
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Export Backup")
                            }
                        },
                        onClick = {
                            showMenu = false
                            onExportBackup()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Import Backup")
                            }
                        },
                        onClick = {
                            showMenu = false
                            onImportBackup()
                        },
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
    )
}

@Composable
private fun MainBottomNavigationBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        NavigationBarItem(
            selected = selectedTab == AppTab.WORKFLOWS,
            onClick = { onTabSelected(AppTab.WORKFLOWS) },
            label = { Text("Workflows", fontSize = 11.sp) },
            icon = {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = "Workflows",
                )
            },
            colors =
                NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        )
        NavigationBarItem(
            selected = selectedTab == AppTab.PRECONDITIONS,
            onClick = { onTabSelected(AppTab.PRECONDITIONS) },
            label = { Text("Preconditions", fontSize = 11.sp) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Preconditions",
                )
            },
            colors =
                NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        )
        NavigationBarItem(
            selected = selectedTab == AppTab.CREDENTIALS,
            onClick = { onTabSelected(AppTab.CREDENTIALS) },
            label = { Text("Vault", fontSize = 11.sp) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Credentials",
                )
            },
            colors =
                NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        )
        NavigationBarItem(
            selected = selectedTab == AppTab.SETTINGS,
            onClick = { onTabSelected(AppTab.SETTINGS) },
            label = { Text("Settings", fontSize = 11.sp) },
            icon = {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                )
            },
            colors =
                NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
        )
    }
}

@Composable
internal fun ActiveExecutionBanner(
    activeState: ActiveExecutionState,
    onViewSheet: () -> Unit,
    onCancelExecution: (() -> Unit)? = null,
) {
    if (!activeState.isExecuting) return
    var showCancelConfirmDialog by remember { mutableStateOf(false) }
    LaunchedEffect(activeState.workflowId, activeState.isExecuting) {
        if (!activeState.isExecuting) {
            showCancelConfirmDialog = false
        }
    }
    val activeStep =
        activeState.steps.lastOrNull { it.state == NodeStepState.EXECUTING }
            ?: activeState.steps.lastOrNull()
    val isQueuedState =
        activeStep?.isQueueWaiting == true ||
            activeStep?.nodeId?.startsWith("queue_waiting") == true

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isQueuedState) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (isQueuedState) {
                    Icon(
                        imageVector = Icons.Default.HourglassTop,
                        contentDescription = "Queued",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Column {
                    Text(
                        text = if (isQueuedState) "⏳ Queued: ${activeState.workflowName}" else "Executing: ${activeState.workflowName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isQueuedState) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = activeStep?.logMessage ?: "Running...",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = (if (isQueuedState) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer).copy(alpha = 0.8f),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onCancelExecution != null) {
                    TextButton(onClick = { showCancelConfirmDialog = true }) {
                        Text(
                            text = "Cancel",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(
                    onClick = onViewSheet,
                ) {
                    Text(
                        text = "View",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        color = if (isQueuedState) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }

    if (showCancelConfirmDialog && onCancelExecution != null) {
        CancelExecutionConfirmDialog(
            targetName = activeState.workflowName,
            title = if (isQueuedState) "Cancel Queued Execution?" else "Cancel Running Execution?",
            message =
                if (isQueuedState) {
                    "Are you sure you want to stop this queued execution of '${activeState.workflowName}'?"
                } else {
                    "Are you sure you want to stop this running execution of '${activeState.workflowName}'?"
                },
            onConfirm = onCancelExecution,
            onDismiss = { showCancelConfirmDialog = false },
        )
    }
}

@Composable
private fun MainTabContent(
    selectedTab: AppTab,
    viewModel: MainViewModel,
    workflows: List<Workflow>,
    preconditions: List<PreconditionEntity>,
    credentials: List<Credential>,
    queuedMessages: List<MessageEntity> = emptyList(),
    queuedWorkflowIds: Set<String> = emptySet(),
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onEditWorkflow: (Workflow) -> Unit,
    onViewLogs: (Workflow) -> Unit,
    onViewQueue: (Workflow) -> Unit,
    onEditPrecondition: (PreconditionEntity) -> Unit,
    onProcessQueueNow: (() -> Unit)? = null,
) {
    when (selectedTab) {
        AppTab.WORKFLOWS -> {
            WorkflowsListScreen(
                workflows = workflows,
                queuedMessages = queuedMessages,
                queuedWorkflowIds = queuedWorkflowIds,
                onToggleWorkflow = { id, enabled ->
                    viewModel.setWorkflowEnabled(id, enabled)
                },
                onDeleteWorkflow = { id ->
                    viewModel.deleteWorkflow(id)
                },
                onEditWorkflow = onEditWorkflow,
                onViewLogs = onViewLogs,
                onViewQueue = onViewQueue,
                onGetLogsForWorkflowFlow = { id -> viewModel.getLogsForWorkflow(id) },
                onClearLogsForWorkflow = { id -> viewModel.clearLogsForWorkflow(id) },
                onDeleteExecutionLog = { id -> viewModel.deleteExecutionLog(id) },
                onProcessQueueNow = onProcessQueueNow,
                onRetryQueuedWorkflow = { log -> viewModel.retryQueuedWorkflow(log) },
                onCancelQueuedWorkflowLog = { id -> viewModel.cancelQueuedWorkflowExecution(id) },
                onCancelQueuedMessage = { id -> viewModel.deleteQueuedMessage(id) },
                onRetryWorkflowFromFailedNode = { wf, failedNodeId, originalLogId, onProgress, onResult ->
                    viewModel.retryWorkflowFromFailedNode(wf, failedNodeId, originalLogId, onProgress, onResult)
                },
            )
        }
        AppTab.PRECONDITIONS -> {
            PreconditionsScreen(
                preconditions = preconditions,
                credentials = credentials,
                workflows = workflows,
                onSavePrecondition = { prec -> viewModel.savePrecondition(prec) },
                onDeletePrecondition = { id -> viewModel.deletePrecondition(id) },
                onTestPreconditionWithProgress = { entity, onProgress, onResult ->
                    viewModel.testPreconditionWithProgress(entity, onProgress, onResult)
                },
                onCancelTestPrecondition = { id ->
                    viewModel.cancelPreconditionTest(id)
                },
                onEditPrecondition = onEditPrecondition,
            )
        }
        AppTab.CREDENTIALS -> {
            CredentialsScreen(
                viewModel = viewModel,
            )
        }
        AppTab.SETTINGS -> {
            SettingsScreen(
                viewModel = viewModel,
                hasSmsPermission = hasSmsPermission,
                onRequestSmsPermission = onRequestSmsPermission,
                hasNotificationPermission = hasNotificationPermission,
                onRequestNotificationPermission = onRequestNotificationPermission,
            )
        }
    }
}
