package dev.zophop.ui.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.MessageEntity
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.generateShortWorkflowId
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Modernized Workflows List Screen.
 */
@Composable
fun WorkflowsListScreen(
    workflows: List<Workflow> = emptyList(),
    queuedMessages: List<MessageEntity> = emptyList(),
    queuedWorkflowIds: Set<String> = emptySet(),
    onToggleWorkflow: ((String, Boolean) -> Unit)? = null,
    onRunManualWorkflow: ((Workflow) -> Unit)? = null,
    onDeleteWorkflow: ((String) -> Unit)? = null,
    onEditWorkflow: (Workflow) -> Unit,
    onViewLogs: ((Workflow) -> Unit)? = null,
    onViewQueue: ((Workflow) -> Unit)? = null,
    onGetLogsForWorkflowFlow: ((String) -> Flow<List<WorkflowExecutionLogEntity>>)? = null,
    onClearLogsForWorkflow: ((String) -> Unit)? = null,
    onDeleteExecutionLog: ((String) -> Unit)? = null,
    onProcessQueueNow: (() -> Unit)? = null,
    onRetryQueuedWorkflow: ((WorkflowExecutionLogEntity) -> Unit)? = null,
    onCancelQueuedWorkflowLog: ((String) -> Unit)? = null,
    onCancelQueuedMessage: ((String) -> Unit)? = null,
    onRetryWorkflowFromFailedNode: (
        (
            Workflow,
            String?,
            String?,
            (List<NodeExecutionStep>) -> Unit,
            (WorkflowExecutionResult) -> Unit,
        ) -> Unit
    )? = null,
) {
    var selectedWorkflowForLogs by remember { mutableStateOf<Workflow?>(null) }
    var selectedWorkflowLogsInitialTab by remember { mutableIntStateOf(0) }
    var showCreateTriggerSheet by remember { mutableStateOf(false) }
    var pendingDeleteWorkflow by remember { mutableStateOf<Workflow?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            if (workflows.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No workflows created yet. Tap '+ Add Workflow' below to choose a trigger.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(workflows, key = { it.id }) { workflow ->
                    WorkflowCardItem(
                        workflow = workflow,
                        isQueued = workflow.id in queuedWorkflowIds,
                        onToggle = { enabled ->
                            onToggleWorkflow?.invoke(workflow.id, enabled)
                        },
                        onRunManual = {
                            onRunManualWorkflow?.invoke(workflow)
                        },
                        onEdit = { onEditWorkflow(workflow) },
                        onViewLogs = {
                            if (onViewLogs != null) {
                                onViewLogs(workflow)
                            } else {
                                selectedWorkflowLogsInitialTab = 0
                                selectedWorkflowForLogs = workflow
                            }
                        },
                        onViewQueue = {
                            if (onViewQueue != null) {
                                onViewQueue(workflow)
                            } else if (onViewLogs != null) {
                                onViewLogs(workflow)
                            } else {
                                selectedWorkflowLogsInitialTab = 1
                                selectedWorkflowForLogs = workflow
                            }
                        },
                        onDelete = { pendingDeleteWorkflow = workflow },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        ExtendedFloatingActionButton(
            onClick = { showCreateTriggerSheet = true },
            icon = { Icon(Icons.Default.Add, contentDescription = "Add Workflow") },
            text = { Text("Add Workflow") },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    if (showCreateTriggerSheet) {
        CreateTriggerSelectionBottomSheet(
            onDismiss = { showCreateTriggerSheet = false },
            onSelectTrigger = { triggerManifest ->
                showCreateTriggerSheet = false
                val triggerNode =
                    WorkflowNode(
                        id = UUID.randomUUID().toString(),
                        name = triggerManifest.displayName,
                        type = triggerManifest.typeId,
                        isEnabled = true,
                        configJson = "{}",
                    )

                val newWf =
                    Workflow(
                        id = generateShortWorkflowId(),
                        name = "${triggerManifest.displayName} Automation",
                        description = "Automated pipeline for ${triggerManifest.displayName}",
                        isEnabled = true,
                        nodes = listOf(triggerNode),
                        connections = emptyList(),
                    )
                onEditWorkflow(newWf)
            },
        )
    }

    selectedWorkflowForLogs?.let { wf ->
        WorkflowLogsBottomSheet(
            workflow = wf,
            logsFlow = onGetLogsForWorkflowFlow?.invoke(wf.id),
            onClearLogs = { onClearLogsForWorkflow?.invoke(wf.id) },
            onDeleteExecutionLog = onDeleteExecutionLog,
            onRetryWorkflowFromFailedNode = onRetryWorkflowFromFailedNode,
            queuedMessages = queuedMessages,
            onRetryQueuedWorkflow = onRetryQueuedWorkflow,
            onCancelQueuedWorkflowLog = onCancelQueuedWorkflowLog,
            onCancelQueuedMessage = onCancelQueuedMessage,
            onProcessQueueNow = onProcessQueueNow,
            initialTab = selectedWorkflowLogsInitialTab,
            onDismiss = { selectedWorkflowForLogs = null },
        )
    }

    pendingDeleteWorkflow?.let { wf ->
        AlertDialog(
            onDismissRequest = { pendingDeleteWorkflow = null },
            title = { Text("Delete Workflow?") },
            text = { Text("Are you sure you want to delete workflow '${wf.name}'?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteWorkflow?.invoke(wf.id)
                        pendingDeleteWorkflow = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteWorkflow = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Compact Modernized Card Item for a Workflow rule in the list.
 */
@Composable
fun WorkflowCardItem(
    workflow: Workflow,
    isQueued: Boolean = false,
    onToggle: (Boolean) -> Unit,
    onRunManual: () -> Unit,
    onEdit: () -> Unit,
    onViewLogs: () -> Unit,
    onViewQueue: (() -> Unit)? = null,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val triggerNode = workflow.nodes.firstOrNull()
    val isManual = triggerNode?.type == NodeType.MANUAL_TRIGGER
    val triggerTitle = triggerNode?.let { WorkflowUiHelper.getNodeTitle(it.type) } ?: "Trigger"
    val triggerIcon = triggerNode?.let { WorkflowUiHelper.getNodeIcon(it.type) } ?: Icons.Default.PlayArrow

    val dismissState = rememberSwipeToDismissBoxState()
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentOnDelete by rememberUpdatedState(onDelete)

    LaunchedEffect(dismissState.settledValue) {
        when (dismissState.settledValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                currentOnDelete()
                dismissState.reset()
            }
            SwipeToDismissBoxValue.StartToEnd -> {
                currentOnEdit()
                dismissState.reset()
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color =
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                }
            val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            val icon = if (direction == SwipeToDismissBoxValue.StartToEnd) Icons.Default.Edit else Icons.Default.Delete
            val text = if (direction == SwipeToDismissBoxValue.StartToEnd) "Edit" else "Delete"
            val tint =
                if (direction ==
                    SwipeToDismissBoxValue.StartToEnd
                ) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(color, shape = RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(imageVector = icon, contentDescription = text, tint = tint, modifier = Modifier.size(16.dp))
                    Text(text = text, color = tint, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        },
    ) {
        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit),
            shape = RoundedCornerShape(14.dp),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = if (workflow.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
                ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Header Row: Trigger Icon, Name, Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (workflow.icon.isNotBlank()) {
                                    Text(
                                        text = workflow.icon,
                                        fontSize = 16.sp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = triggerIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = workflow.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = triggerTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Switch(
                        checked = workflow.isEnabled,
                        onCheckedChange = { targetEnabled ->
                            onToggle(targetEnabled)
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }

                // Pipeline Step Summary Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isQueued) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.clickable { onViewQueue?.invoke() ?: onViewLogs() },
                        ) {
                            Text(
                                text = "⏳ Queued",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "${workflow.nodes.size} Nodes",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "${workflow.connections.size} Links",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                // Bottom Action Bar: Run / Logs / Edit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isManual) {
                            OutlinedButton(
                                onClick = onRunManual,
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("Run", fontSize = 11.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = onViewLogs,
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Logs", fontSize = 11.sp)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTriggerSelectionBottomSheet(
    onDismiss: () -> Unit,
    onSelectTrigger: (dev.zophop.plugin.api.NodePluginManifest) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unifiedRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .UnifiedPluginRegistry(context)
        }
    var triggerManifests by remember { mutableStateOf<List<dev.zophop.plugin.api.NodePluginManifest>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val allManifests = unifiedRegistry.getAllManifests()
        triggerManifests = allManifests.filter { it.category == dev.zophop.plugin.api.NodeCategory.TRIGGER }
    }

    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Select Workflow Trigger",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            if (triggerManifests.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                triggerManifests.forEach { manifest ->
                    TriggerSelectionCard(
                        title = manifest.displayName,
                        description = manifest.description,
                        icon = WorkflowUiHelper.getNodeIcon(manifest.typeId),
                        onClick = { onSelectTrigger(manifest) },
                    )
                }
            }
        }
    }
}

@Composable
fun TriggerSelectionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private sealed interface LogsSheetConfirmation {
    data object ClearAllHistory : LogsSheetConfirmation

    data class DeleteSingleHistoryLog(
        val logId: String,
    ) : LogsSheetConfirmation

    data class CancelQueuedWorkflow(
        val logId: String,
        val isRunning: Boolean = false,
    ) : LogsSheetConfirmation

    data class CancelQueuedMessage(
        val messageId: String,
        val sender: String,
    ) : LogsSheetConfirmation
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowLogsBottomSheet(
    workflow: Workflow,
    logsFlow: Flow<List<WorkflowExecutionLogEntity>>?,
    onClearLogs: () -> Unit,
    onDeleteExecutionLog: ((String) -> Unit)? = null,
    onRetryWorkflowFromFailedNode: (
        (
            Workflow,
            String?,
            String?,
            (List<NodeExecutionStep>) -> Unit,
            (WorkflowExecutionResult) -> Unit,
        ) -> Unit
    )? = null,
    queuedMessages: List<MessageEntity> = emptyList(),
    onRetryQueuedWorkflow: ((WorkflowExecutionLogEntity) -> Unit)? = null,
    onCancelQueuedWorkflowLog: ((String) -> Unit)? = null,
    onCancelQueuedMessage: ((String) -> Unit)? = null,
    onProcessQueueNow: (() -> Unit)? = null,
    initialTab: Int = 0,
    onDismiss: () -> Unit,
) {
    val logs by (logsFlow ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val historyLogs = remember(logs) { logs.filter { it.status != "QUEUED" } }
    val queuedWorkflowLogs = remember(logs) { logs.filter { it.status == "QUEUED" } }
    val queueCount = queuedWorkflowLogs.size + queuedMessages.size
    var selectedTab by remember(workflow.id, initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var historyFilter by remember(workflow.id) { mutableStateOf("ALL") }
    var retryingNodeId by remember { mutableStateOf<String?>(null) }
    var retrySteps by remember { mutableStateOf<List<NodeExecutionStep>>(emptyList()) }
    var retryResult by remember { mutableStateOf<WorkflowExecutionResult?>(null) }
    var showRetryStepperSheet by remember { mutableStateOf(false) }
    var pendingConfirmation by remember { mutableStateOf<LogsSheetConfirmation?>(null) }

    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Execution History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = workflow.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selectedTab == 0 && historyLogs.isNotEmpty()) {
                        TextButton(onClick = { pendingConfirmation = LogsSheetConfirmation.ClearAllHistory }) {
                            Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("History (${logs.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Queue ($queueCount)") },
                )
            }

            if (selectedTab == 0) {
                WorkflowHistoryTabContent(
                    workflow = workflow,
                    logs = logs,
                    historyFilter = historyFilter,
                    onFilterChange = { historyFilter = it },
                    onRetryWorkflowFromFailedNode = onRetryWorkflowFromFailedNode,
                    onRetryStarted = { failedNodeId ->
                        retryingNodeId = failedNodeId
                        showRetryStepperSheet = true
                        retrySteps = emptyList()
                        retryResult = null
                    },
                    onRetryStepsUpdate = { steps -> retrySteps = steps },
                    onRetryResultUpdate = { res -> retryResult = res },
                    onCancelExecutionRequest =
                        onCancelQueuedWorkflowLog?.let {
                            { log ->
                                pendingConfirmation =
                                    LogsSheetConfirmation.CancelQueuedWorkflow(
                                        logId = log.id,
                                        isRunning = log.status == "RUNNING",
                                    )
                            }
                        },
                    onDeleteExecutionRequest =
                        onDeleteExecutionLog?.let {
                            { logId -> pendingConfirmation = LogsSheetConfirmation.DeleteSingleHistoryLog(logId) }
                        },
                )
            } else {
                WorkflowQueueTabContent(
                    workflowName = workflow.name,
                    queueCount = queueCount,
                    queuedWorkflowLogs = queuedWorkflowLogs,
                    queuedMessages = queuedMessages,
                    onRetryQueuedWorkflow = onRetryQueuedWorkflow,
                    onCancelQueuedWorkflowRequest =
                        onCancelQueuedWorkflowLog?.let {
                            { logId -> pendingConfirmation = LogsSheetConfirmation.CancelQueuedWorkflow(logId) }
                        },
                    onCancelQueuedMessageRequest =
                        onCancelQueuedMessage?.let {
                            { msg ->
                                pendingConfirmation =
                                    LogsSheetConfirmation.CancelQueuedMessage(
                                        messageId = msg.id,
                                        sender = msg.sender,
                                    )
                            }
                        },
                    onProcessQueueNow = onProcessQueueNow,
                )
            }
        }
    }

    pendingConfirmation?.let { confirmation ->
        WorkflowLogsConfirmationDialog(
            confirmation = confirmation,
            workflowName = workflow.name,
            historyLogCount = historyLogs.size,
            onClearLogs = onClearLogs,
            onDeleteExecutionLog = onDeleteExecutionLog,
            onCancelQueuedWorkflowLog = onCancelQueuedWorkflowLog,
            onCancelQueuedMessage = onCancelQueuedMessage,
            onDismiss = { pendingConfirmation = null },
        )
    }

    if (showRetryStepperSheet) {
        TestRunLogBottomSheet(
            workflowName = workflow.name,
            steps = retrySteps,
            isTesting = retryResult == null,
            onDismiss = { showRetryStepperSheet = false },
        )
    }
}

@Composable
private fun ColumnScope.WorkflowHistoryTabContent(
    workflow: Workflow,
    logs: List<WorkflowExecutionLogEntity>,
    historyFilter: String,
    onFilterChange: (String) -> Unit,
    onRetryWorkflowFromFailedNode: (
        (
            Workflow,
            String?,
            String?,
            (List<NodeExecutionStep>) -> Unit,
            (WorkflowExecutionResult) -> Unit,
        ) -> Unit
    )?,
    onRetryStarted: (String?) -> Unit,
    onRetryStepsUpdate: (List<NodeExecutionStep>) -> Unit,
    onRetryResultUpdate: (WorkflowExecutionResult) -> Unit,
    onCancelExecutionRequest: ((WorkflowExecutionLogEntity) -> Unit)?,
    onDeleteExecutionRequest: ((String) -> Unit)?,
) {
    if (logs.isEmpty()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No execution logs recorded yet for this workflow.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val passedCount = remember(logs) { logs.count { it.status == "SUCCESS" } }
    val issueCount =
        remember(logs) {
            logs.count { it.status != "SUCCESS" && it.status != "QUEUED" && it.status != "RUNNING" }
        }
    val displayedLogs =
        remember(logs, historyFilter) {
            when (historyFilter) {
                "PASSED" -> logs.filter { it.status == "SUCCESS" }
                "ISSUES" -> logs.filter { it.status != "SUCCESS" && it.status != "QUEUED" && it.status != "RUNNING" }
                else -> logs
            }
        }

    if (logs.size > 1 && (passedCount > 0 || issueCount > 0)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = historyFilter == "ALL",
                onClick = { onFilterChange("ALL") },
                label = { Text("All (${logs.size})", fontSize = 11.sp) },
            )
            if (passedCount > 0) {
                FilterChip(
                    selected = historyFilter == "PASSED",
                    onClick = { onFilterChange("PASSED") },
                    label = { Text("Passed ($passedCount)", fontSize = 11.sp) },
                )
            }
            if (issueCount > 0) {
                FilterChip(
                    selected = historyFilter == "ISSUES",
                    onClick = { onFilterChange("ISSUES") },
                    label = { Text("Issues ($issueCount)", fontSize = 11.sp) },
                )
            }
        }
    }

    Column(
        modifier =
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        displayedLogs.forEachIndexed { index, log ->
            WorkflowExecutionCard(
                log = log,
                isInitiallyExpanded = index == 0,
                workflowName = workflow.name,
                onRetryWorkflowFromFailedNode =
                    onRetryWorkflowFromFailedNode?.let { retryFn ->
                        { failedNodeId ->
                            onRetryStarted(failedNodeId)
                            retryFn(
                                workflow,
                                failedNodeId,
                                log.id,
                                onRetryStepsUpdate,
                                onRetryResultUpdate,
                            )
                        }
                    },
                onCancelQueuedExecution =
                    if ((log.status == "QUEUED" || log.status == "RUNNING") && onCancelExecutionRequest != null) {
                        { onCancelExecutionRequest(log) }
                    } else {
                        null
                    },
                onDeleteExecution =
                    if (log.status != "QUEUED" && onDeleteExecutionRequest != null) {
                        { onDeleteExecutionRequest(log.id) }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun ColumnScope.WorkflowQueueTabContent(
    workflowName: String,
    queueCount: Int,
    queuedWorkflowLogs: List<WorkflowExecutionLogEntity>,
    queuedMessages: List<MessageEntity>,
    onRetryQueuedWorkflow: ((WorkflowExecutionLogEntity) -> Unit)?,
    onCancelQueuedWorkflowRequest: ((String) -> Unit)?,
    onCancelQueuedMessageRequest: ((MessageEntity) -> Unit)?,
    onProcessQueueNow: (() -> Unit)?,
) {
    if (queueCount == 0) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No queued executions waiting.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Column(
        modifier =
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        queuedWorkflowLogs.forEach { log ->
            QueuedWorkflowLogCard(
                workflowName = workflowName,
                log = log,
                onRetry = onRetryQueuedWorkflow?.let { fn -> { fn(log) } },
                onCancel = onCancelQueuedWorkflowRequest?.let { fn -> { fn(log.id) } },
            )
        }
        queuedMessages.forEach { msg ->
            QueuedMessageCard(
                message = msg,
                onCancel = onCancelQueuedMessageRequest?.let { fn -> { fn(msg) } },
            )
        }
    }

    if (onProcessQueueNow != null) {
        Button(
            onClick = onProcessQueueNow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Process Queue Now")
        }
    }
}

@Composable
private fun WorkflowLogsConfirmationDialog(
    confirmation: LogsSheetConfirmation,
    workflowName: String,
    historyLogCount: Int,
    onClearLogs: () -> Unit,
    onDeleteExecutionLog: ((String) -> Unit)?,
    onCancelQueuedWorkflowLog: ((String) -> Unit)?,
    onCancelQueuedMessage: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val dialogSpec =
        when (confirmation) {
            is LogsSheetConfirmation.ClearAllHistory ->
                ConfirmationDialogSpec(
                    title = "Clear Execution History?",
                    message =
                        "Remove $historyLogCount execution log(s) for '$workflowName'? " +
                            "Pending queued executions will remain in the queue.",
                    confirmLabel = "Clear History",
                    dismissLabel = "Cancel",
                    onConfirm = onClearLogs,
                )
            is LogsSheetConfirmation.DeleteSingleHistoryLog ->
                ConfirmationDialogSpec(
                    title = "Delete Execution Log?",
                    message = "Are you sure you want to remove this execution log from history?",
                    confirmLabel = "Delete",
                    dismissLabel = "Cancel",
                    onConfirm = { onDeleteExecutionLog?.invoke(confirmation.logId) },
                )
            is LogsSheetConfirmation.CancelQueuedWorkflow ->
                ConfirmationDialogSpec(
                    title = if (confirmation.isRunning) "Cancel Running Execution?" else "Cancel Queued Execution?",
                    message =
                        if (confirmation.isRunning) {
                            "Are you sure you want to stop this running execution of '$workflowName'?"
                        } else {
                            "Are you sure you want to cancel this pending execution of '$workflowName' " +
                                "and remove it from the queue?"
                        },
                    confirmLabel = "Cancel Execution",
                    dismissLabel = "Keep",
                    onConfirm = { onCancelQueuedWorkflowLog?.invoke(confirmation.logId) },
                )
            is LogsSheetConfirmation.CancelQueuedMessage ->
                ConfirmationDialogSpec(
                    title = "Remove Queued Message?",
                    message = "Are you sure you want to cancel and remove the queued message from '${confirmation.sender}'?",
                    confirmLabel = "Remove",
                    dismissLabel = "Keep",
                    onConfirm = { onCancelQueuedMessage?.invoke(confirmation.messageId) },
                )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogSpec.title) },
        text = { Text(dialogSpec.message) },
        confirmButton = {
            Button(
                onClick = {
                    dialogSpec.onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(dialogSpec.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dialogSpec.dismissLabel)
            }
        },
    )
}

private data class ConfirmationDialogSpec(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val dismissLabel: String,
    val onConfirm: () -> Unit,
)

@Composable
private fun QueuedWorkflowLogCard(
    workflowName: String,
    log: WorkflowExecutionLogEntity,
    onRetry: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        text = "QUEUED",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    text = workflowName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            val reasonText =
                log.errorReason?.takeIf { it.isNotBlank() }
                    ?: "Waiting for preconditions to resolve..."
            Text(
                text = reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val elapsedMinutes = ((System.currentTimeMillis() - log.timestamp) / 60000L).coerceAtLeast(0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Queued ${elapsedMinutes}m ago",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onRetry != null) {
                        TextButton(onClick = onRetry) {
                            Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Cancel",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageCard(
    message: MessageEntity,
    onCancel: (() -> Unit)?,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = "QUEUED",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        text = message.sender,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (onCancel != null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            val reasonText =
                message.errorMessage?.takeIf { it.isNotBlank() }
                    ?: "Waiting for preconditions to resolve..."
            Text(
                text = reasonText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val elapsedMinutes = ((System.currentTimeMillis() - message.receivedAt) / 60000L).coerceAtLeast(0)
            Text(
                text = "Queued ${elapsedMinutes}m ago • Attempt ${message.retryCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
