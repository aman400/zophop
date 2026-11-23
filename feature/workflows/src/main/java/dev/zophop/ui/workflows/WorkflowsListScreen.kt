package dev.zophop.ui.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.MessageEntity
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Modernized Workflows List Screen.
 */
@Composable
fun WorkflowsListScreen(
    workflows: List<Workflow> = emptyList(),
    queuedMessages: List<MessageEntity> = emptyList(),
    onToggleWorkflow: ((String, Boolean) -> Unit)? = null,
    onRunManualWorkflow: ((Workflow) -> Unit)? = null,
    onDeleteWorkflow: ((String) -> Unit)? = null,
    onEditWorkflow: (Workflow) -> Unit,
    onViewLogs: ((Workflow) -> Unit)? = null,
    onGetLogsForWorkflowFlow: ((String) -> Flow<List<WorkflowExecutionLogEntity>>)? = null,
    onClearLogsForWorkflow: ((String) -> Unit)? = null,
    onViewPendingQueue: (() -> Unit)? = null,
    onProcessQueueNow: (() -> Unit)? = null,
    onRetryWorkflowFromFailedNode: (
        (
            Workflow,
            String?,
            (List<NodeExecutionStep>) -> Unit,
            (WorkflowExecutionResult) -> Unit,
        ) -> Unit
    )? = null,
) {
    var selectedWorkflowForLogs by remember { mutableStateOf<Workflow?>(null) }
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

            if (queuedMessages.isNotEmpty()) {
                item {
                    PendingQueueCard(
                        queuedCount = queuedMessages.size,
                        onViewQueue = onViewPendingQueue,
                        onProcessNow = onProcessQueueNow,
                    )
                }
            }

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
                        id = UUID.randomUUID().toString(),
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
            onRetryWorkflowFromFailedNode = onRetryWorkflowFromFailedNode,
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
    onToggle: (Boolean) -> Unit,
    onRunManual: () -> Unit,
    onEdit: () -> Unit,
    onViewLogs: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val triggerNode = workflow.nodes.firstOrNull()
    val isManual = triggerNode?.type == NodeType.MANUAL_TRIGGER
    val triggerTitle = triggerNode?.let { WorkflowUiHelper.getNodeTitle(it.type) } ?: "Trigger"
    val triggerIcon = triggerNode?.let { WorkflowUiHelper.getNodeIcon(it.type) } ?: Icons.Default.PlayArrow

    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { dismissValue ->
                if (dismissValue == SwipeToDismissBoxValue.EndToStart) {
                    onDelete()
                    false
                } else if (dismissValue == SwipeToDismissBoxValue.StartToEnd) {
                    onEdit()
                    false
                } else {
                    false
                }
            },
        )

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
                    horizontalArrangement = Arrangement.SpaceBetween,
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
                                Icon(
                                    imageVector = triggerIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
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

@Composable
fun PendingQueueCard(
    queuedCount: Int,
    onViewQueue: (() -> Unit)?,
    onProcessNow: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = "Pending Queue: $queuedCount waiting",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Executions waiting for preconditions to be satisfied",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                if (onProcessNow != null) {
                    TextButton(onClick = onProcessNow) {
                        Text("Retry", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                if (onViewQueue != null) {
                    TextButton(onClick = onViewQueue) {
                        Text("View", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowLogsBottomSheet(
    workflow: Workflow,
    logsFlow: Flow<List<WorkflowExecutionLogEntity>>?,
    onClearLogs: () -> Unit,
    onRetryWorkflowFromFailedNode: (
        (
            Workflow,
            String?,
            (List<NodeExecutionStep>) -> Unit,
            (WorkflowExecutionResult) -> Unit,
        ) -> Unit
    )? = null,
    onDismiss: () -> Unit,
) {
    val logs by (logsFlow ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    var retryingNodeId by remember { mutableStateOf<String?>(null) }
    var retrySteps by remember { mutableStateOf<List<NodeExecutionStep>>(emptyList()) }
    var retryResult by remember { mutableStateOf<WorkflowExecutionResult?>(null) }
    var showRetryStepperSheet by remember { mutableStateOf(false) }

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
                Column {
                    Text(
                        text = "Execution History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = workflow.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (logs.isNotEmpty()) {
                        TextButton(onClick = onClearLogs) {
                            Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            if (logs.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("No execution logs recorded yet for this workflow.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    logs.forEachIndexed { index, log ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val isSuccess = log.status == "SUCCESS"
                                val steps =
                                    try {
                                        log.logs.toNodeExecutionSteps() ?: listOf(
                                            NodeExecutionStep(
                                                nodeId = "legacy",
                                                nodeName = "Execution History",
                                                nodeType = NodeType.MANUAL_TRIGGER,
                                                state = if (isSuccess) NodeStepState.SUCCESS else NodeStepState.FAILED,
                                                logMessage = log.logs,
                                            ),
                                        )
                                    } catch (_: Exception) {
                                        listOf(
                                            NodeExecutionStep(
                                                nodeId = "legacy",
                                                nodeName = "Execution History",
                                                nodeType = NodeType.MANUAL_TRIGGER,
                                                state = if (isSuccess) NodeStepState.SUCCESS else NodeStepState.FAILED,
                                                logMessage = log.logs,
                                            ),
                                        )
                                    }
                                var expanded by remember { mutableStateOf(index == 0) }

                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Expand",
                                            modifier = Modifier.size(20.dp).padding(end = 4.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        val isQueued = log.status == "QUEUED"
                                        val chipContainerColor =
                                            when {
                                                isSuccess -> MaterialTheme.colorScheme.primaryContainer
                                                isQueued -> MaterialTheme.colorScheme.tertiaryContainer
                                                else -> MaterialTheme.colorScheme.errorContainer
                                            }
                                        val chipContentColor =
                                            when {
                                                isSuccess -> MaterialTheme.colorScheme.onPrimaryContainer
                                                isQueued -> MaterialTheme.colorScheme.onTertiaryContainer
                                                else -> MaterialTheme.colorScheme.onErrorContainer
                                            }
                                        Surface(
                                            color = chipContainerColor,
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                text = if (isSuccess) "SUCCESS" else log.status,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = chipContentColor,
                                            )
                                        }
                                    }

                                    Text(
                                        text =
                                            java.text
                                                .SimpleDateFormat(
                                                    "MMM dd, HH:mm:ss",
                                                    LocalLocale.current.platformLocale,
                                                ).format(java.util.Date(log.timestamp)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                if (expanded) {
                                    Box(modifier = Modifier.padding(top = 8.dp)) {
                                        NodeExecutionStepper(
                                            steps = steps,
                                            onRetryFromNode =
                                                if (onRetryWorkflowFromFailedNode != null) {
                                                    { failedNodeId ->
                                                        retryingNodeId = failedNodeId
                                                        showRetryStepperSheet = true
                                                        retrySteps = emptyList()
                                                        retryResult = null
                                                        onRetryWorkflowFromFailedNode(
                                                            workflow,
                                                            failedNodeId,
                                                            { steps -> retrySteps = steps },
                                                            { res -> retryResult = res },
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
