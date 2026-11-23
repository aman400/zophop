package dev.zophop.ui.workflows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.EditorDarkColorScheme
import dev.zophop.core.ui.EditorLightColorScheme
import dev.zophop.core.ui.components.InfoTooltipField
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(
    workflow: Workflow,
    credentials: List<Credential> = emptyList(),
    vaultPreconditions: List<PreconditionEntity> = emptyList(),
    hasNotificationListenerPermission: Boolean = true,
    hasPostNotificationsPermission: Boolean = true,
    hasSmsPermission: Boolean = true,
    onBack: () -> Unit,
    onSaveWorkflow: (Workflow) -> Unit,
    onTestRunWorkflow: (Workflow, (String) -> Unit) -> Unit,
    onTestRunWorkflowWithProgress: ((Workflow, (List<NodeExecutionStep>) -> Unit, (WorkflowExecutionResult) -> Unit) -> Unit)? = null,
    onCancelWorkflowExecution: ((String) -> Unit)? = null,
    onSaveCredential: ((Credential) -> Unit)? = null,
    onSavePrecondition: ((PreconditionEntity) -> Unit)? = null,
    onRequestCreatePrecondition: ((onCreated: (PreconditionEntity) -> Unit) -> Unit)? = null,
) {
    var name by remember { mutableStateOf(workflow.name) }
    var description by remember { mutableStateOf(workflow.description) }
    var isEnabled by remember { mutableStateOf(workflow.isEnabled) }
    var nodes by remember { mutableStateOf(workflow.nodes) }
    var connections by remember { mutableStateOf(workflow.connections) }

    var editingNode by remember { mutableStateOf<WorkflowNode?>(null) }
    var pendingDeleteNode by remember { mutableStateOf<WorkflowNode?>(null) }
    var showAddNodeSheet by remember { mutableStateOf(false) }
    var unsavedNodeIds by remember { mutableStateOf(setOf<String>()) }
    var testSteps by remember { mutableStateOf<List<NodeExecutionStep>>(emptyList()) }
    var showTestStepperSheet by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }

    val hasWorkflowChanges =
        workflow != workflow.copy(
            name = name,
            description = description,
            isEnabled = isEnabled,
            nodes = nodes,
            connections = connections,
        )
    val hasUnsavedChanges = hasWorkflowChanges || unsavedNodeIds.isNotEmpty()

    var showDiscardDialog by remember { mutableStateOf(false) }

    val handleBack = {
        if (hasUnsavedChanges) {
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    BackHandler(enabled = editingNode != null) {
        editingNode = null
    }

    BackHandler(enabled = showAddNodeSheet) {
        showAddNodeSheet = false
    }

    BackHandler(enabled = editingNode == null && !showAddNodeSheet) {
        handleBack()
    }

    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) EditorDarkColorScheme else EditorLightColorScheme,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                editingNode != null -> {
                    WorkflowEditingNodeSheet(
                        node = editingNode!!,
                        nodes = nodes,
                        connections = connections,
                        credentials = credentials,
                        vaultPreconditions = vaultPreconditions,
                        hasNotificationListenerPermission = hasNotificationListenerPermission,
                        hasPostNotificationsPermission = hasPostNotificationsPermission,
                        hasSmsPermission = hasSmsPermission,
                        onDismiss = {
                            val currentEditingId = editingNode?.id
                            if (currentEditingId != null && unsavedNodeIds.contains(currentEditingId)) {
                                nodes = nodes.filterNot { it.id == currentEditingId }
                                val newConns = mutableListOf<WorkflowConnection>()
                                for (i in 0 until nodes.size - 1) {
                                    newConns.add(WorkflowConnection(fromNodeId = nodes[i].id, toNodeId = nodes[i + 1].id))
                                }
                                connections = newConns
                                unsavedNodeIds = unsavedNodeIds - currentEditingId
                            }
                            editingNode = null
                        },
                        onSave = { updatedNode ->
                            nodes = nodes.map { if (it.id == updatedNode.id) updatedNode else it }
                            unsavedNodeIds = unsavedNodeIds - updatedNode.id
                            editingNode = null
                        },
                        onSaveCredential = onSaveCredential,
                        onSavePrecondition = onSavePrecondition,
                        onRequestCreatePrecondition = onRequestCreatePrecondition,
                    )
                }
                showAddNodeSheet -> {
                    WorkflowAddNodeSheet(
                        nodes = nodes,
                        onBack = { showAddNodeSheet = false },
                        onSelectNodeType = { type ->
                            val newNodeId = UUID.randomUUID().toString()
                            val newNode =
                                WorkflowNode(
                                    id = newNodeId,
                                    name = WorkflowUiHelper.getNodeTitle(type),
                                    type = type,
                                    isEnabled = true,
                                    configJson = WorkflowUiHelper.getDefaultConfigForType(type),
                                )
                            val lastId = nodes.lastOrNull()?.id
                            nodes = nodes + newNode
                            if (lastId != null) {
                                connections = connections + WorkflowConnection(fromNodeId = lastId, toNodeId = newNodeId)
                            }
                            showAddNodeSheet = false
                            unsavedNodeIds = unsavedNodeIds + newNode.id
                            editingNode = newNode
                        },
                    )
                }
                else -> {
                    Scaffold(
                        topBar = {
                            WorkflowEditorTopBar(
                                nodesCount = nodes.size,
                                connectionsCount = connections.size,
                                hasUnsavedChanges = hasUnsavedChanges,
                                onBack = handleBack,
                                onSaveClick = { showSaveConfirmDialog = true },
                            )
                        },
                    ) { innerPadding ->
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding),
                        ) {
                            LazyColumn(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                item { Spacer(modifier = Modifier.height(12.dp)) }

                                item {
                                    WorkflowPipelineBuilderHeader()
                                }

                                item {
                                    InfoTooltipField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = "Workflow Name",
                                        tooltipText = "Descriptive name for this automated workflow pipeline.",
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                    )
                                }

                                itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                                    val isFirst = index == 0
                                    val isUnsaved = unsavedNodeIds.contains(node.id)

                                    WorkflowNodeCardItem(
                                        node = node,
                                        isFirst = isFirst,
                                        index = index,
                                        totalNodes = nodes.size,
                                        isUnsaved = isUnsaved,
                                        hasNotificationListenerPermission = hasNotificationListenerPermission,
                                        hasPostNotificationsPermission = hasPostNotificationsPermission,
                                        hasSmsPermission = hasSmsPermission,
                                        onToggleNode = { enabled ->
                                            nodes = nodes.map { if (it.id == node.id) it.copy(isEnabled = enabled) else it }
                                        },
                                        onEditNode = { editingNode = node },
                                        onDeleteNode = { pendingDeleteNode = node },
                                        onMoveUp = {
                                            if (index > 1) {
                                                val (newNodes, newConns) = reorderWorkflowNodes(nodes, connections, index, index - 1)
                                                nodes = newNodes
                                                connections = newConns
                                            }
                                        },
                                        onMoveDown = {
                                            if (index < nodes.size - 1) {
                                                val (newNodes, newConns) = reorderWorkflowNodes(nodes, connections, index, index + 1)
                                                nodes = newNodes
                                                connections = newConns
                                            }
                                        },
                                    )
                                }

                                item { Spacer(modifier = Modifier.height(96.dp)) }
                            }

                            WorkflowEditorFabs(
                                onTestRun = {
                                    isTesting = true
                                    showTestStepperSheet = true
                                    testSteps = emptyList()
                                    val testWorkflow =
                                        workflow.copy(
                                            name = name,
                                            description = description,
                                            isEnabled = isEnabled,
                                            nodes = nodes,
                                            connections = connections,
                                        )
                                    onTestRunWorkflowWithProgress?.invoke(
                                        testWorkflow,
                                        { steps -> testSteps = steps },
                                        { _ -> isTesting = false },
                                    ) ?: run {
                                        onTestRunWorkflow(testWorkflow) { _ -> isTesting = false }
                                    }
                                },
                                onAddStep = { showAddNodeSheet = true },
                            )
                        }
                    }
                }
            }

            if (showTestStepperSheet) {
                TestRunLogBottomSheet(
                    workflowName = name,
                    steps = testSteps,
                    isTesting = isTesting,
                    onCancelExecution = { onCancelWorkflowExecution?.invoke(workflow.id) },
                    onDismiss = { showTestStepperSheet = false },
                )
            }

            if (showSaveConfirmDialog) {
                WorkflowSaveConfirmDialog(
                    workflow = workflow,
                    currentName = name,
                    currentDescription = description,
                    currentNodes = nodes,
                    onDismiss = { showSaveConfirmDialog = false },
                    onConfirm = {
                        showSaveConfirmDialog = false
                        val savedWorkflow =
                            workflow.copy(
                                name = name,
                                description = description,
                                isEnabled = isEnabled,
                                nodes = nodes,
                                connections = connections,
                                updatedAt = System.currentTimeMillis(),
                            )
                        unsavedNodeIds = emptySet()
                        onSaveWorkflow(savedWorkflow)
                    },
                )
            }

            pendingDeleteNode?.let { nodeToDelete ->
                WorkflowDeleteNodeDialog(
                    nodeName = nodeToDelete.name,
                    onDismiss = { pendingDeleteNode = null },
                    onConfirm = {
                        val newNodes = nodes.filterNot { it.id == nodeToDelete.id }
                        nodes = newNodes
                        val newConns = mutableListOf<WorkflowConnection>()
                        for (i in 0 until newNodes.size - 1) {
                            newConns.add(WorkflowConnection(fromNodeId = newNodes[i].id, toNodeId = newNodes[i + 1].id))
                        }
                        connections = newConns
                        pendingDeleteNode = null
                    },
                )
            }

            if (showDiscardDialog) {
                WorkflowDiscardDialog(
                    onDismiss = { showDiscardDialog = false },
                    onConfirmDiscard = {
                        showDiscardDialog = false
                        onBack()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowEditorTopBar(
    nodesCount: Int,
    connectionsCount: Int,
    hasUnsavedChanges: Boolean,
    onBack: () -> Unit,
    onSaveClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Pipeline Builder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$nodesCount Nodes • $connectionsCount Connections",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            Button(
                onClick = onSaveClick,
                enabled = hasUnsavedChanges,
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Save")
            }
            Spacer(modifier = Modifier.width(8.dp))
        },
    )
}

@Composable
private fun WorkflowSaveConfirmDialog(
    workflow: Workflow,
    currentName: String,
    currentDescription: String,
    currentNodes: List<WorkflowNode>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val nameChanged = currentName != workflow.name
    val descChanged = currentDescription != workflow.description
    val originalNodeIds = workflow.nodes.map { it.id }.toSet()
    val currentNodeIds = currentNodes.map { it.id }.toSet()
    val addedNodeIds = currentNodeIds - originalNodeIds
    val removedNodeIds = originalNodeIds - currentNodeIds
    val addedNodes = currentNodes.filter { addedNodeIds.contains(it.id) }
    val removedNodes = workflow.nodes.filter { removedNodeIds.contains(it.id) }
    val modifiedNodes =
        currentNodes.filter { node ->
            val orig = workflow.nodes.find { it.id == node.id }
            orig != null && (orig.name != node.name || orig.configJson != node.configJson || orig.isEnabled != node.isEnabled)
        }
    val reordered = currentNodes.map { it.id } != workflow.nodes.map { it.id }
    val hasNoNodeChanges = addedNodes.isEmpty() && removedNodes.isEmpty() && modifiedNodes.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Workflow Changes") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Here is a summary of new changes to be saved:")
                if (nameChanged) {
                    Text("• Name: '${workflow.name}' → '$currentName'", fontWeight = FontWeight.Bold)
                }
                if (descChanged) {
                    Text("• Description updated")
                }
                if (addedNodes.isNotEmpty()) {
                    Text(
                        "• Added Nodes (${addedNodes.size}):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    addedNodes.forEach {
                        Text("  + ${it.name} [${it.type}]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                if (removedNodes.isNotEmpty()) {
                    Text(
                        "• Removed Nodes (${removedNodes.size}):",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                    )
                    removedNodes.forEach {
                        Text("  - ${it.name} [${it.type}]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                if (modifiedNodes.isNotEmpty()) {
                    Text("• Modified Nodes (${modifiedNodes.size}):", fontWeight = FontWeight.Bold)
                    modifiedNodes.forEach {
                        Text("  ~ ${it.name} [${it.type}]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                if (reordered && hasNoNodeChanges) {
                    Text("• Pipeline steps reordered", fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Confirm & Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun WorkflowDeleteNodeDialog(
    nodeName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Pipeline Node?") },
        text = { Text("Are you sure you want to delete node '$nodeName'?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun WorkflowDiscardDialog(
    onDismiss: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard Changes?") },
        text = { Text("You have unsaved changes in this workflow. Are you sure you want to discard them?") },
        confirmButton = {
            Button(
                onClick = onConfirmDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Discard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep Editing")
            }
        },
    )
}

private fun reorderWorkflowNodes(
    nodesList: List<WorkflowNode>,
    connectionsList: List<WorkflowConnection>,
    fromIndex: Int,
    toIndex: Int,
): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    val validIndices = 1 until nodesList.size
    if (fromIndex !in validIndices || toIndex !in validIndices) {
        return Pair(nodesList, connectionsList)
    }
    val newNodes = nodesList.toMutableList()
    val item = newNodes.removeAt(fromIndex)
    newNodes.add(toIndex, item)

    val newConns = mutableListOf<WorkflowConnection>()
    for (i in 0 until newNodes.size - 1) {
        newConns.add(WorkflowConnection(fromNodeId = newNodes[i].id, toNodeId = newNodes[i + 1].id))
    }
    return Pair(newNodes, newConns)
}

@Composable
private fun WorkflowPipelineBuilderHeader() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column {
                Text(
                    text = "Pipeline Builder Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "Configure trigger rules, parsers, and delivery actions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun WorkflowEditorFabs(
    onTestRun: () -> Unit,
    onAddStep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ExtendedFloatingActionButton(
            onClick = onTestRun,
            icon = { Icon(Icons.Default.PlayArrow, contentDescription = "Test Run") },
            text = { Text("Test Run") },
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        ExtendedFloatingActionButton(
            onClick = onAddStep,
            icon = { Icon(Icons.Default.Add, contentDescription = "Add Step") },
            text = { Text("Add Step") },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun WorkflowEditingNodeSheet(
    node: WorkflowNode,
    nodes: List<WorkflowNode>,
    connections: List<WorkflowConnection>,
    credentials: List<Credential>,
    vaultPreconditions: List<PreconditionEntity>,
    hasNotificationListenerPermission: Boolean,
    hasPostNotificationsPermission: Boolean,
    hasSmsPermission: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorkflowNode) -> Unit,
    onSaveCredential: ((Credential) -> Unit)?,
    onSavePrecondition: ((PreconditionEntity) -> Unit)?,
    onRequestCreatePrecondition: ((onCreated: (PreconditionEntity) -> Unit) -> Unit)?,
) {
    val availableUpstreamVars =
        remember(node.id, nodes, connections) {
            dev.zophop.domain.engine.WorkflowVariableResolver.getAvailableUpstreamVariables(
                targetNodeId = node.id,
                nodes = nodes,
                connections = connections,
            )
        }

    NodeConfigBottomSheet(
        node = node,
        availableVariables = availableUpstreamVars,
        credentials = credentials,
        vaultPreconditions = vaultPreconditions,
        showPreconditionsGuard = !WorkflowUiHelper.isConditionalNode(node.type),
        hasNotificationListenerPermission = hasNotificationListenerPermission,
        hasPostNotificationsPermission = hasPostNotificationsPermission,
        hasSmsPermission = hasSmsPermission,
        onDismiss = onDismiss,
        onSave = onSave,
        onSaveCredential = onSaveCredential,
        onSavePrecondition = onSavePrecondition,
        onRequestCreatePrecondition = onRequestCreatePrecondition,
    )
}

@Composable
private fun WorkflowAddNodeSheet(
    nodes: List<WorkflowNode>,
    onBack: () -> Unit,
    onSelectNodeType: (String) -> Unit,
) {
    val activeTriggerType =
        nodes.firstOrNull { it.type.contains("TRIGGER", ignoreCase = true) }?.type
            ?: nodes.firstOrNull()?.type
    AddNodeScreen(
        workflowTriggerType = activeTriggerType,
        onBack = onBack,
        onSelectNodeType = onSelectNodeType,
    )
}
