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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
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
    ) : MainScreenDestination()

    data class EditPrecondition(
        val precondition: PreconditionEntity?,
    ) : MainScreenDestination()
}

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
    var activeEditingPrecondition by remember { mutableStateOf<PreconditionEntity?>(null) }
    var showAddPreconditionScreen by remember { mutableStateOf(false) }
    var activeCreatePreconditionCallback by remember { mutableStateOf<((PreconditionEntity) -> Unit)?>(null) }

    val uiState by viewModel.uiState.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    val preconditions by viewModel.preconditions.collectAsState()
    val activeExecutionState by viewModel.activeExecutionState.collectAsState()
    var showActiveExecutionSheet by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(uiState.selectedWorkflowForLogs) {
        if (uiState.selectedWorkflowForLogs != null) {
            activeLogsWorkflow = uiState.selectedWorkflowForLogs
            viewModel.clearSelectedWorkflowForLogs()
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
                activeLogsWorkflow != null -> MainScreenDestination.ViewLogs(activeLogsWorkflow!!)
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
                    onSavePrecondition = { newPrec ->
                        viewModel.savePrecondition(newPrec)
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
                    onRetryWorkflowFromFailedNode = { wf, failedNodeId, onProgress, onResult ->
                        viewModel.retryWorkflowFromFailedNode(wf, failedNodeId, onProgress, onResult)
                    },
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
                Scaffold(
                    topBar = {
                        MainTopAppBar(
                            selectedTab = selectedTab,
                            workflows = workflows,
                            preconditions = preconditions,
                            credentials = credentials,
                            isEngineEnabled = uiState.isEnabled,
                        )
                    },
                    bottomBar = {
                        MainBottomNavigationBar(
                            selectedTab = selectedTab,
                            onTabSelected = { selectedTab = it },
                        )
                    },
                ) { innerPadding ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                    ) {
                        MainTabContent(
                            selectedTab = selectedTab,
                            viewModel = viewModel,
                            workflows = workflows,
                            preconditions = preconditions,
                            credentials = credentials,
                            hasSmsPermission = hasSmsPermission,
                            onRequestSmsPermission = onRequestSmsPermission,
                            hasNotificationPermission = hasNotificationPermission,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onEditWorkflow = { activeEditingWorkflow = it },
                            onViewLogs = { activeLogsWorkflow = it },
                            onEditPrecondition = { activeEditingPrecondition = it },
                        )
                    }

                    activeExecutionState?.let { activeState ->
                        ActiveExecutionBanner(
                            activeState = activeState,
                            onViewSheet = { showActiveExecutionSheet = true },
                        )
                    }
                }
            }
        }
    }

    if (showActiveExecutionSheet && activeExecutionState != null) {
        val activeState = activeExecutionState!!
        val wf = workflows.find { it.id == activeState.workflowId }
        if (wf != null) {
            TestRunLogBottomSheet(
                workflowName = activeState.workflowName,
                steps = activeState.steps,
                isTesting = activeState.isExecuting,
                onCancelExecution = { viewModel.cancelWorkflowExecution(wf.id) },
                onDismiss = { showActiveExecutionSheet = false },
            )
        }
    }

    if (activeCreatePreconditionCallback != null) {
        PreconditionEditorScreen(
            precondition = null,
            credentials = credentials,
            onTestPreconditionWithProgress = { entity, onProgress, onResult ->
                viewModel.testPreconditionWithProgress(entity, onProgress, onResult)
            },
            onDismiss = { activeCreatePreconditionCallback = null },
            onSave = { newPrec ->
                viewModel.savePrecondition(newPrec)
                activeCreatePreconditionCallback?.invoke(newPrec)
                activeCreatePreconditionCallback = null
            },
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
) {
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
                        Text(
                            text = "$autoActiveCount Auto Active • $manualCount Manual",
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
                    modifier = Modifier.padding(end = 12.dp),
                )
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
private fun ActiveExecutionBanner(
    activeState: ActiveExecutionState,
    onViewSheet: () -> Unit,
) {
    if (!activeState.isExecuting) return

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 80.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Column {
                    Text(
                        text = "Executing: ${activeState.workflowName}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        text = "Step: ${activeState.steps.lastOrNull()?.nodeName ?: "Running..."}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    )
                }
            }

            TextButton(
                onClick = onViewSheet,
            ) {
                Text("View", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun MainTabContent(
    selectedTab: AppTab,
    viewModel: MainViewModel,
    workflows: List<Workflow>,
    preconditions: List<PreconditionEntity>,
    credentials: List<Credential>,
    hasSmsPermission: Boolean,
    onRequestSmsPermission: () -> Unit,
    hasNotificationPermission: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onEditWorkflow: (Workflow) -> Unit,
    onViewLogs: (Workflow) -> Unit,
    onEditPrecondition: (PreconditionEntity) -> Unit,
) {
    when (selectedTab) {
        AppTab.WORKFLOWS -> {
            WorkflowsListScreen(
                workflows = workflows,
                onToggleWorkflow = { id, enabled ->
                    viewModel.setWorkflowEnabled(id, enabled)
                },
                onDeleteWorkflow = { id ->
                    viewModel.deleteWorkflow(id)
                },
                onEditWorkflow = onEditWorkflow,
                onViewLogs = onViewLogs,
                onGetLogsForWorkflowFlow = { id -> viewModel.getLogsForWorkflow(id) },
                onClearLogsForWorkflow = { id -> viewModel.clearLogsForWorkflow(id) },
                onRetryWorkflowFromFailedNode = { wf, failedNodeId, onProgress, onResult ->
                    viewModel.retryWorkflowFromFailedNode(wf, failedNodeId, onProgress, onResult)
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
