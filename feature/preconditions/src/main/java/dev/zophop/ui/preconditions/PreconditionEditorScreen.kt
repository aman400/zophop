package dev.zophop.ui.preconditions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zophop.core.ui.components.InfoTooltipField
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.toDomain
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.PreconditionType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowConnection
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.ui.workflows.NodeConfigBottomSheet
import dev.zophop.ui.workflows.WorkflowUiHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Modernized editor screen for creating or modifying a Precondition Entity as a Chained Pipeline.
 * All preconditions are treated as chained pipelines, supporting single-node or multi-node pipelines.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreconditionEditorScreen(
    precondition: PreconditionEntity?,
    credentials: List<Credential> = emptyList(),
    workflows: List<Workflow> = emptyList(),
    onTestPreconditionWithProgress: ((PreconditionEntity, (List<NodeExecutionStep>) -> Unit, (PreconditionResult) -> Unit) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (PreconditionEntity) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val localContext = LocalContext.current

    var showNotificationPermissionRationaleDialog by remember { mutableStateOf(false) }

    val linkedWorkflows =
        remember(workflows, precondition?.id) {
            findLinkedWorkflows(workflows, precondition?.id)
        }

    var name by remember { mutableStateOf(precondition?.name ?: "Precondition Guard") }
    var isEnabled by remember { mutableStateOf(precondition?.isEnabled ?: true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showSaveConfirmDialog by remember { mutableStateOf(false) }

    // Failure policy fields
    var onFailureAction by remember { mutableStateOf(precondition?.onFailureAction ?: "FAIL_IMMEDIATELY") }
    var maxRetries by remember { mutableStateOf((precondition?.maxRetries ?: 3).toString()) }
    var retryIntervalSeconds by remember { mutableStateOf((precondition?.retryIntervalSeconds ?: 30).toString()) }
    var retryStrategy by remember { mutableStateOf(precondition?.retryStrategy ?: "FIXED") }
    var queueTimeoutMinutes by remember { mutableStateOf((precondition?.queueTimeoutMinutes ?: 10).toString()) }
    var showNotificationOnFailure by remember { mutableStateOf(precondition?.showNotificationOnFailure ?: false) }

    // Initial chain nodes & connections conversion (seamless legacy migration to chained pipeline)
    val initialNodesAndConns =
        remember(precondition) {
            convertLegacyToNodesAndConns(precondition)
        }

    var chainNodes by remember { mutableStateOf(initialNodesAndConns.first) }
    var chainConnections by remember { mutableStateOf(initialNodesAndConns.second) }

    var editingChainNode by remember { mutableStateOf<WorkflowNode?>(null) }
    var showAddNodeDialog by remember { mutableStateOf(false) }
    var nodePendingDeletion by remember { mutableStateOf<WorkflowNode?>(null) }

    LaunchedEffect(precondition) {
        syncPreconditionState(
            precondition = precondition,
            hasNodes = chainNodes.isNotEmpty(),
            onPreconditionPresent = {
                name = it.name
                isEnabled = it.isEnabled
                onFailureAction = it.onFailureAction
                maxRetries = it.maxRetries.toString()
                retryIntervalSeconds = it.retryIntervalSeconds.toString()
                retryStrategy = it.retryStrategy
                queueTimeoutMinutes = it.queueTimeoutMinutes.toString()
                showNotificationOnFailure = it.showNotificationOnFailure
            },
            onShowAddNode = { showAddNodeDialog = true },
        )
    }

    // Testing state & bottom sheet
    var isTestingSheet by remember { mutableStateOf(false) }
    var sheetTestResult by remember { mutableStateOf<PreconditionResult?>(null) }
    var sheetTestSteps by remember { mutableStateOf<List<NodeExecutionStep>>(emptyList()) }
    var showTestStepperSheet by remember { mutableStateOf(false) }

    val pipelineJson =
        remember(chainNodes, chainConnections) {
            buildPipelineJson(chainNodes, chainConnections)
        }

    val hasUnsavedChanges =
        remember(
            precondition,
            name,
            isEnabled,
            pipelineJson,
            onFailureAction,
            maxRetries,
            retryIntervalSeconds,
            retryStrategy,
            queueTimeoutMinutes,
            showNotificationOnFailure,
        ) {
            hasPreconditionChanges(
                precondition = precondition,
                name = name,
                isEnabled = isEnabled,
                pipelineJson = pipelineJson,
                onFailureAction = onFailureAction,
                maxRetries = maxRetries,
                retryIntervalSeconds = retryIntervalSeconds,
                retryStrategy = retryStrategy,
                queueTimeoutMinutes = queueTimeoutMinutes,
                showNotificationOnFailure = showNotificationOnFailure,
                chainNodesCount = chainNodes.size,
            )
        }

    BackHandler(enabled = true) {
        handleDismissRequest(
            isTesting = isTestingSheet,
            hasUnsavedChanges = hasUnsavedChanges,
            onShowDiscard = { showDiscardDialog = true },
            onDismiss = onDismiss,
        )
    }

    fun reorderStep(index: Int, delta: Int) {
        computeMovedNodes(chainNodes, index, delta)?.let { (nodes, conns) ->
            chainNodes = nodes
            chainConnections = conns
        }
    }

    Scaffold(
        topBar = {
            PreconditionEditorTopBar(
                isEditing = precondition != null,
                isTesting = isTestingSheet,
                hasUnsavedChanges = hasUnsavedChanges,
                onCloseClick = {
                    handleDismissRequest(
                        isTesting = isTestingSheet,
                        hasUnsavedChanges = hasUnsavedChanges,
                        onShowDiscard = { showDiscardDialog = true },
                        onDismiss = onDismiss,
                    )
                },
                onSaveClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    val error = validatePreconditionSave(name, chainNodes.isNotEmpty())
                    errorMessage = error
                    if (error == null) {
                        showSaveConfirmDialog = true
                    }
                },
            )
        },
        floatingActionButton = {
            PreconditionEditorFabs(
                isTesting = isTestingSheet,
                onTestClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    handleTestClick(
                        isTestingSheet = isTestingSheet,
                        hasNodes = chainNodes.isNotEmpty(),
                        onShowSheet = { showTestStepperSheet = true },
                        onError = { errorMessage = it },
                        onStartTest = {
                            isTestingSheet = true
                            showTestStepperSheet = true
                            sheetTestResult = null
                            sheetTestSteps = emptyList()
                            startPreconditionTest(
                                preconditionId = precondition?.id,
                                name = name,
                                isEnabled = isEnabled,
                                pipelineJson = pipelineJson,
                                onFailureAction = onFailureAction,
                                maxRetries = maxRetries,
                                retryIntervalSeconds = retryIntervalSeconds,
                                retryStrategy = retryStrategy,
                                queueTimeoutMinutes = queueTimeoutMinutes,
                                showNotificationOnFailure = showNotificationOnFailure,
                                onTestPreconditionWithProgress = onTestPreconditionWithProgress,
                                onStepsUpdate = { sheetTestSteps = it },
                                onResult = { res ->
                                    isTestingSheet = false
                                    sheetTestResult = res
                                },
                            )
                        },
                    )
                },
                onAddStepClick = { showAddNodeDialog = true },
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { innerPadding ->
        PreconditionEditorBody(
            innerPadding = innerPadding,
            isTesting = isTestingSheet,
            errorMessage = errorMessage,
            onDismissError = { errorMessage = null },
            name = name,
            onNameChange = { name = it },
            isEnabled = isEnabled,
            onEnabledChange = { isEnabled = it },
            onFailureAction = onFailureAction,
            onFailureActionChanged = { onFailureAction = it },
            maxRetries = maxRetries,
            onMaxRetriesChanged = { maxRetries = it },
            retryIntervalSeconds = retryIntervalSeconds,
            onRetryIntervalSecondsChanged = { retryIntervalSeconds = it },
            retryStrategy = retryStrategy,
            onRetryStrategyChanged = { retryStrategy = it },
            queueTimeoutMinutes = queueTimeoutMinutes,
            onQueueTimeoutMinutesChanged = { queueTimeoutMinutes = it },
            showNotificationOnFailure = showNotificationOnFailure,
            onShowNotificationOnFailureChanged = { showNotificationOnFailure = it },
            onShowNotificationRationale = { showNotificationPermissionRationaleDialog = true },
            chainNodes = chainNodes,
            onAddStep = { showAddNodeDialog = true },
            onEditStep = { node -> editingChainNode = node },
            onDeleteStep = { node -> nodePendingDeletion = node },
            onMoveUp = { index -> reorderStep(index, -1) },
            onMoveDown = { index -> reorderStep(index, 1) },
        )
    }

    PreconditionEditorDialogHost(
        showTestStepperSheet = showTestStepperSheet,
        isTestingSheet = isTestingSheet,
        sheetTestSteps = sheetTestSteps,
        name = name,
        onDismissTestStepper = { showTestStepperSheet = false },
        showSaveConfirmDialog = showSaveConfirmDialog,
        precondition = precondition,
        isEnabled = isEnabled,
        pipelineJson = pipelineJson,
        onFailureAction = onFailureAction,
        maxRetries = maxRetries,
        retryIntervalSeconds = retryIntervalSeconds,
        retryStrategy = retryStrategy,
        queueTimeoutMinutes = queueTimeoutMinutes,
        showNotificationOnFailure = showNotificationOnFailure,
        chainNodes = chainNodes,
        linkedWorkflows = linkedWorkflows,
        onConfirmSave = {
            keyboardController?.hide()
            focusManager.clearFocus()
            showSaveConfirmDialog = false
            onSave(it)
        },
        onDismissSaveConfirm = { showSaveConfirmDialog = false },
        showNotificationRationale = showNotificationPermissionRationaleDialog,
        onDismissNotificationRationale = { showNotificationPermissionRationaleDialog = false },
        onOpenSettings = {
            showNotificationPermissionRationaleDialog = false
            openAppDetailsSettings(localContext)
        },
        editingChainNode = editingChainNode,
        credentials = credentials,
        onDismissEditNode = { editingChainNode = null },
        onSaveEditNode = { updated ->
            chainNodes = chainNodes.map { if (it.id == updated.id) updated else it }
            editingChainNode = null
        },
        showAddNodeDialog = showAddNodeDialog,
        onDismissAddNode = { showAddNodeDialog = false },
        onNodeAdded = { newNode, updatedName, conn ->
            name = updatedName
            chainNodes = chainNodes + newNode
            if (conn != null) {
                chainConnections = chainConnections + conn
            }
            showAddNodeDialog = false
            editingChainNode = newNode
        },
        showDiscardDialog = showDiscardDialog,
        onDismissDiscard = { showDiscardDialog = false },
        onConfirmDiscard = {
            showDiscardDialog = false
            onDismiss()
        },
        nodePendingDeletion = nodePendingDeletion,
        onDismissDeleteStep = { nodePendingDeletion = null },
        onConfirmDeleteStep = { nodeToDelete ->
            val (newNodes, newConns) = computeNodesAfterDeletion(chainNodes, nodeToDelete)
            chainNodes = newNodes
            chainConnections = newConns
            nodePendingDeletion = null
        },
    )
}

private inline fun syncPreconditionState(
    precondition: PreconditionEntity?,
    hasNodes: Boolean,
    onPreconditionPresent: (PreconditionEntity) -> Unit,
    onShowAddNode: () -> Unit,
) {
    if (precondition != null) {
        onPreconditionPresent(precondition)
    } else if (!hasNodes) {
        onShowAddNode()
    }
}

private fun handleDismissRequest(
    isTesting: Boolean,
    hasUnsavedChanges: Boolean,
    onShowDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTesting) return
    if (hasUnsavedChanges) {
        onShowDiscard()
    } else {
        onDismiss()
    }
}

private fun validatePreconditionSave(
    name: String,
    hasNodes: Boolean,
): String? {
    if (name.isBlank()) return "Precondition name cannot be blank."
    if (!hasNodes) return "Precondition pipeline must have at least one step."
    return null
}

private fun handleTestClick(
    isTestingSheet: Boolean,
    hasNodes: Boolean,
    onShowSheet: () -> Unit,
    onError: (String) -> Unit,
    onStartTest: () -> Unit,
) {
    if (isTestingSheet) {
        onShowSheet()
    } else if (!hasNodes) {
        onError("Add at least one step to test this precondition pipeline.")
    } else {
        onStartTest()
    }
}

@Suppress("LongParameterList")
private fun startPreconditionTest(
    preconditionId: String?,
    name: String,
    isEnabled: Boolean,
    pipelineJson: String,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String,
    queueTimeoutMinutes: String,
    showNotificationOnFailure: Boolean,
    onTestPreconditionWithProgress: ((PreconditionEntity, (List<NodeExecutionStep>) -> Unit, (PreconditionResult) -> Unit) -> Unit)?,
    onStepsUpdate: (List<NodeExecutionStep>) -> Unit,
    onResult: (PreconditionResult) -> Unit,
) {
    val tempEntity =
        buildTestingEntity(
            preconditionId = preconditionId,
            name = name,
            isEnabled = isEnabled,
            pipelineJson = pipelineJson,
            onFailureAction = onFailureAction,
            maxRetries = maxRetries,
            retryIntervalSeconds = retryIntervalSeconds,
            retryStrategy = retryStrategy,
            queueTimeoutMinutes = queueTimeoutMinutes,
            showNotificationOnFailure = showNotificationOnFailure,
        )
    onTestPreconditionWithProgress?.invoke(tempEntity, onStepsUpdate, onResult)
}

private fun computeMovedNodes(
    chainNodes: List<WorkflowNode>,
    index: Int,
    delta: Int,
): Pair<List<WorkflowNode>, List<WorkflowConnection>>? {
    val target = index + delta
    if (target < 0 || target >= chainNodes.size) return null
    return reorderChainNodesList(chainNodes, index, target)
}

private fun computeNodesAfterDeletion(
    chainNodes: List<WorkflowNode>,
    nodeToDelete: WorkflowNode,
): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    val newNodes = chainNodes.filterNot { it.id == nodeToDelete.id }
    val newConns = mutableListOf<WorkflowConnection>()
    for (i in 0 until newNodes.size - 1) {
        newConns.add(WorkflowConnection(fromNodeId = newNodes[i].id, toNodeId = newNodes[i + 1].id))
    }
    return newNodes to newConns
}

private fun openAppDetailsSettings(context: Context) {
    try {
        val intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}

@Suppress("LongParameterList")
@Composable
private fun PreconditionEditorDialogHost(
    showTestStepperSheet: Boolean,
    isTestingSheet: Boolean,
    sheetTestSteps: List<NodeExecutionStep>,
    name: String,
    onDismissTestStepper: () -> Unit,
    showSaveConfirmDialog: Boolean,
    precondition: PreconditionEntity?,
    isEnabled: Boolean,
    pipelineJson: String,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String,
    queueTimeoutMinutes: String,
    showNotificationOnFailure: Boolean,
    chainNodes: List<WorkflowNode>,
    linkedWorkflows: List<Workflow>,
    onConfirmSave: (PreconditionEntity) -> Unit,
    onDismissSaveConfirm: () -> Unit,
    showNotificationRationale: Boolean,
    onDismissNotificationRationale: () -> Unit,
    onOpenSettings: () -> Unit,
    editingChainNode: WorkflowNode?,
    credentials: List<Credential>,
    onDismissEditNode: () -> Unit,
    onSaveEditNode: (WorkflowNode) -> Unit,
    showAddNodeDialog: Boolean,
    onDismissAddNode: () -> Unit,
    onNodeAdded: (WorkflowNode, String, WorkflowConnection?) -> Unit,
    showDiscardDialog: Boolean,
    onDismissDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
    nodePendingDeletion: WorkflowNode?,
    onDismissDeleteStep: () -> Unit,
    onConfirmDeleteStep: (WorkflowNode) -> Unit,
) {
    if (showTestStepperSheet) {
        PreconditionExecutionStepperBottomSheet(
            preconditionName = name.ifBlank { "Precondition Test" },
            steps = sheetTestSteps,
            isTesting = isTestingSheet,
            onDismiss = onDismissTestStepper,
        )
    }

    if (showSaveConfirmDialog) {
        PreconditionSaveConfirmDialogWrapper(
            precondition = precondition,
            name = name,
            isEnabled = isEnabled,
            pipelineJson = pipelineJson,
            onFailureAction = onFailureAction,
            maxRetries = maxRetries,
            retryIntervalSeconds = retryIntervalSeconds,
            retryStrategy = retryStrategy,
            queueTimeoutMinutes = queueTimeoutMinutes,
            showNotificationOnFailure = showNotificationOnFailure,
            chainNodes = chainNodes,
            linkedWorkflows = linkedWorkflows,
            onConfirmSave = onConfirmSave,
            onDismiss = onDismissSaveConfirm,
        )
    }

    if (showNotificationRationale) {
        PreconditionNotificationRationaleDialog(
            onDismissRequest = onDismissNotificationRationale,
            onOpenSettings = onOpenSettings,
        )
    }

    PreconditionNodeConfigWrapper(
        node = editingChainNode,
        credentials = credentials,
        onDismiss = onDismissEditNode,
        onSave = onSaveEditNode,
    )

    AddChainNodeDialogWrapper(
        showDialog = showAddNodeDialog,
        hasExistingNodes = chainNodes.isNotEmpty(),
        currentName = name,
        lastNodeId = chainNodes.lastOrNull()?.id,
        onDismiss = onDismissAddNode,
        onNodeAdded = onNodeAdded,
    )

    if (showDiscardDialog) {
        PreconditionDiscardDialog(
            onDismissRequest = onDismissDiscard,
            onConfirmDiscard = onConfirmDiscard,
        )
    }

    nodePendingDeletion?.let { nodeToDelete ->
        if (chainNodes.firstOrNull()?.id == nodeToDelete.id) {
            onDismissDeleteStep()
        } else {
            PreconditionDeleteStepDialog(
                nodeName = nodeToDelete.name,
                onDismissRequest = onDismissDeleteStep,
                onConfirmDelete = { onConfirmDeleteStep(nodeToDelete) },
            )
        }
    }
}

private fun convertLegacyToNodesAndConns(
    precondition: PreconditionEntity?,
): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    if (precondition == null) return emptyList<WorkflowNode>() to emptyList<WorkflowConnection>()
    val domain = precondition.toDomain()
    if (domain is Precondition.ChainCheck && domain.nodes.isNotEmpty()) {
        return domain.nodes to domain.connections
    }
    return when (precondition.type) {
        PreconditionType.VPN_CHECK.name -> {
            val id = UUID.randomUUID().toString()
            listOf(
                WorkflowNode(
                    id = id,
                    name = "VPN Check",
                    type = NodeType.VPN_CHECK,
                    isEnabled = true,
                    configJson = WorkflowUiHelper.getDefaultConfigForType(NodeType.VPN_CHECK),
                ),
            ) to emptyList()
        }
        PreconditionType.GPS_CHECK.name -> {
            val id = UUID.randomUUID().toString()
            listOf(
                WorkflowNode(
                    id = id,
                    name = "GPS Location Check",
                    type = NodeType.GPS_CHECK,
                    isEnabled = true,
                    configJson = WorkflowUiHelper.getDefaultConfigForType(NodeType.GPS_CHECK),
                ),
            ) to emptyList()
        }
        PreconditionType.BATTERY_CHECK.name -> {
            val id = UUID.randomUUID().toString()
            listOf(
                WorkflowNode(
                    id = id,
                    name = "Battery Level Check",
                    type = NodeType.BATTERY_CHECK,
                    isEnabled = true,
                    configJson =
                        JSONObject(WorkflowUiHelper.getDefaultConfigForType(NodeType.BATTERY_CHECK))
                            .apply {
                                if (precondition.minBatteryPercent > 0) {
                                    put("minBatteryPercent", precondition.minBatteryPercent.toString())
                                }
                                put("requireCharging", precondition.requireCharging.toString())
                            }.toString(),
                ),
            ) to emptyList()
        }
        PreconditionType.SIM_CHECK.name -> {
            val id = UUID.randomUUID().toString()
            listOf(
                WorkflowNode(
                    id = id,
                    name = "SIM Card Check",
                    type = NodeType.SIM_CHECK,
                    isEnabled = true,
                    configJson =
                        JSONObject(WorkflowUiHelper.getDefaultConfigForType(NodeType.SIM_CHECK))
                            .apply {
                                if (precondition.requiredCarrier.isNotBlank()) {
                                    put("requiredCarrier", precondition.requiredCarrier)
                                }
                            }.toString(),
                ),
            ) to emptyList()
        }
        PreconditionType.HEALTH_CHECK.name -> {
            val httpId = UUID.randomUUID().toString()
            val condId = UUID.randomUUID().toString()
            val httpNode =
                WorkflowNode(
                    id = httpId,
                    name = "HTTP Health Probe",
                    type = NodeType.HTTP_ACTION,
                    isEnabled = true,
                    configJson =
                        JSONObject()
                            .apply {
                                put("url", precondition.targetUrl.ifBlank { "https://example.com/health" })
                                put("method", precondition.method.ifBlank { "GET" })
                                put("headers", precondition.headers)
                                if (precondition.credentialId.isNotBlank()) put("credentialId", precondition.credentialId)
                            }.toString(),
                )
            val condNode =
                WorkflowNode(
                    id = condId,
                    name = "Expected Status Check",
                    type = NodeType.CONDITION,
                    isEnabled = true,
                    configJson =
                        JSONObject()
                            .apply {
                                put("variable", "http_status")
                                put("operator", "EQUALS")
                                put("value", precondition.expectedStatus.toString())
                            }.toString(),
                )
            listOf(httpNode, condNode) to listOf(WorkflowConnection(fromNodeId = httpId, toNodeId = condId))
        }
        else -> {
            val id = UUID.randomUUID().toString()
            listOf(
                WorkflowNode(
                    id = id,
                    name = "Internet & Network Check",
                    type = NodeType.INTERNET_CHECK,
                    isEnabled = true,
                    configJson = WorkflowUiHelper.getDefaultConfigForType(NodeType.INTERNET_CHECK),
                ),
            ) to emptyList()
        }
    }
}

@Composable
private fun PreconditionMetaFields(
    name: String,
    onNameChange: (String) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isTesting: Boolean,
    onFailureAction: String,
    onFailureActionChanged: (String) -> Unit,
    maxRetries: String,
    onMaxRetriesChanged: (String) -> Unit,
    retryIntervalSeconds: String,
    onRetryIntervalSecondsChanged: (String) -> Unit,
    retryStrategy: String,
    onRetryStrategyChanged: (String) -> Unit,
    queueTimeoutMinutes: String,
    onQueueTimeoutMinutesChanged: (String) -> Unit,
    showNotificationOnFailure: Boolean,
    onShowNotificationOnFailureChanged: (Boolean) -> Unit,
    onShowNotificationRationale: () -> Unit,
) {
    InfoTooltipField(
        value = name,
        onValueChange = onNameChange,
        label = "Precondition Name",
        tooltipText = "Descriptive title identifying this precondition guard rule.",
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = !isTesting,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Precondition Enabled",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Switch(
            checked = isEnabled,
            onCheckedChange = onEnabledChange,
            enabled = !isTesting,
        )
    }

    Spacer(modifier = Modifier.height(4.dp))

    FailurePolicyAccordion(
        onFailureAction = onFailureAction,
        onFailureActionChanged = onFailureActionChanged,
        maxRetries = maxRetries,
        onMaxRetriesChanged = onMaxRetriesChanged,
        retryIntervalSeconds = retryIntervalSeconds,
        onRetryIntervalSecondsChanged = onRetryIntervalSecondsChanged,
        retryStrategy = retryStrategy,
        onRetryStrategyChanged = onRetryStrategyChanged,
        queueTimeoutMinutes = queueTimeoutMinutes,
        onQueueTimeoutMinutesChanged = onQueueTimeoutMinutesChanged,
        showNotificationOnFailure = showNotificationOnFailure,
        onShowNotificationOnFailureChanged = onShowNotificationOnFailureChanged,
        onShowNotificationRationale = onShowNotificationRationale,
    )
}

@Composable
private fun PreconditionPipelineStepsSection(
    chainNodes: List<WorkflowNode>,
    isTesting: Boolean,
    onAddStep: () -> Unit,
    onEditStep: (WorkflowNode) -> Unit,
    onDeleteStep: (WorkflowNode) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Pipeline Steps (${chainNodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Sequential check pipeline. Can be a single check node or multi-node chained checks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (chainNodes.isEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "No pipeline check steps configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Add at least one check step (e.g. Internet Check, GPS Check, Battery Check, or HTTP Action).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onAddStep) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add First Step")
                    }
                }
            }
        } else {
            chainNodes.forEachIndexed { index, node ->
                val isLast = index == chainNodes.size - 1
                val isRootNode = index == 0

                androidx.compose.runtime.key(node.id) {
                    PreconditionChainNodeItem(
                        node = node,
                        index = index,
                        isRootNode = isRootNode,
                        isLast = isLast,
                        isTesting = isTesting,
                        onEdit = { onEditStep(node) },
                        onDelete = { onDeleteStep(node) },
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) },
                    )
                }

                if (!isLast) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Next Step",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreconditionEditorTopBar(
    isEditing: Boolean,
    isTesting: Boolean,
    hasUnsavedChanges: Boolean,
    onCloseClick: () -> Unit,
    onSaveClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (isEditing) "Edit Precondition" else "Add Precondition")
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onCloseClick,
                enabled = !isTesting,
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        },
        actions = {
            Button(
                onClick = onSaveClick,
                enabled = hasUnsavedChanges && !isTesting,
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
private fun PreconditionEditorFabs(
    isTesting: Boolean,
    onTestClick: () -> Unit,
    onAddStepClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ExtendedFloatingActionButton(
            onClick = onTestClick,
            icon = {
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Test")
                }
            },
            text = { Text(if (isTesting) "Verifying..." else "Test") },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        ExtendedFloatingActionButton(
            onClick = onAddStepClick,
            icon = { Icon(Icons.Default.Add, contentDescription = "Add Step") },
            text = { Text("Add Step") },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun PreconditionDiscardDialog(
    onDismissRequest: () -> Unit,
    onConfirmDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Discard Changes?") },
        text = { Text("You have unsaved changes in this precondition. Are you sure you want to discard them?") },
        confirmButton = {
            Button(
                onClick = onConfirmDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Discard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Keep Editing")
            }
        },
    )
}

@Composable
private fun PreconditionDeleteStepDialog(
    nodeName: String,
    onDismissRequest: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Delete Pipeline Step") },
        text = { Text("Are you sure you want to delete '$nodeName'? This action cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PreconditionNotificationRationaleDialog(
    onDismissRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Notification Permission Required") },
        text = {
            Text(
                "To post failure notification alerts when this precondition fails, notification permissions must be granted in App Settings.",
            )
        },
        confirmButton = {
            Button(onClick = onOpenSettings) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        },
    )
}

private fun buildPipelineJson(
    chainNodes: List<WorkflowNode>,
    chainConnections: List<WorkflowConnection>,
): String =
    JSONObject().apply {
        val nodesArr = JSONArray()
        chainNodes.forEach { node ->
            nodesArr.put(
                JSONObject().apply {
                    put("id", node.id)
                    put("name", node.name)
                    put("type", node.type)
                    put("isEnabled", node.isEnabled)
                    put("configJson", node.configJson)
                },
            )
        }
        put("nodes", nodesArr)

        val connArr = JSONArray()
        chainConnections.forEach { conn ->
            connArr.put(
                JSONObject().apply {
                    put("fromNodeId", conn.fromNodeId)
                    put("toNodeId", conn.toNodeId)
                    put("outputPort", conn.outputPort)
                },
            )
        }
        put("connections", connArr)
    }.toString()

private fun hasPreconditionChanges(
    precondition: PreconditionEntity?,
    name: String,
    isEnabled: Boolean,
    pipelineJson: String,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String,
    queueTimeoutMinutes: String,
    showNotificationOnFailure: Boolean,
    chainNodesCount: Int,
): Boolean {
    if (precondition == null) {
        return name.isNotBlank() ||
            !isEnabled ||
            onFailureAction != "FAIL_IMMEDIATELY" ||
            maxRetries != "3" ||
            retryIntervalSeconds != "30" ||
            retryStrategy != "FIXED" ||
            queueTimeoutMinutes != "10" ||
            showNotificationOnFailure ||
            chainNodesCount > 0
    }
    return name != precondition.name ||
        isEnabled != precondition.isEnabled ||
        onFailureAction != precondition.onFailureAction ||
        maxRetries != precondition.maxRetries.toString() ||
        retryIntervalSeconds != precondition.retryIntervalSeconds.toString() ||
        retryStrategy != precondition.retryStrategy ||
        queueTimeoutMinutes != precondition.queueTimeoutMinutes.toString() ||
        showNotificationOnFailure != precondition.showNotificationOnFailure ||
        pipelineJson != precondition.pipelineJson
}

@Composable
private fun PreconditionEditorBody(
    innerPadding: PaddingValues,
    isTesting: Boolean,
    errorMessage: String?,
    onDismissError: () -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onFailureAction: String,
    onFailureActionChanged: (String) -> Unit,
    maxRetries: String,
    onMaxRetriesChanged: (String) -> Unit,
    retryIntervalSeconds: String,
    onRetryIntervalSecondsChanged: (String) -> Unit,
    retryStrategy: String,
    onRetryStrategyChanged: (String) -> Unit,
    queueTimeoutMinutes: String,
    onQueueTimeoutMinutesChanged: (String) -> Unit,
    showNotificationOnFailure: Boolean,
    onShowNotificationOnFailureChanged: (Boolean) -> Unit,
    onShowNotificationRationale: () -> Unit,
    chainNodes: List<WorkflowNode>,
    onAddStep: () -> Unit,
    onEditStep: (WorkflowNode) -> Unit,
    onDeleteStep: (WorkflowNode) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (isTesting) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Precondition Verification in Progress... Editing is disabled during test execution.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        errorMessage?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismissError, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        PreconditionMetaFields(
            name = name,
            onNameChange = onNameChange,
            isEnabled = isEnabled,
            onEnabledChange = onEnabledChange,
            isTesting = isTesting,
            onFailureAction = onFailureAction,
            onFailureActionChanged = onFailureActionChanged,
            maxRetries = maxRetries,
            onMaxRetriesChanged = onMaxRetriesChanged,
            retryIntervalSeconds = retryIntervalSeconds,
            onRetryIntervalSecondsChanged = onRetryIntervalSecondsChanged,
            retryStrategy = retryStrategy,
            onRetryStrategyChanged = onRetryStrategyChanged,
            queueTimeoutMinutes = queueTimeoutMinutes,
            onQueueTimeoutMinutesChanged = onQueueTimeoutMinutesChanged,
            showNotificationOnFailure = showNotificationOnFailure,
            onShowNotificationOnFailureChanged = onShowNotificationOnFailureChanged,
            onShowNotificationRationale = onShowNotificationRationale,
        )

        Spacer(modifier = Modifier.height(4.dp))

        PreconditionPipelineStepsSection(
            chainNodes = chainNodes,
            isTesting = isTesting,
            onAddStep = onAddStep,
            onEditStep = onEditStep,
            onDeleteStep = onDeleteStep,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )

        Spacer(modifier = Modifier.height(96.dp))
    }
}

@Composable
private fun PreconditionSaveConfirmDialogWrapper(
    precondition: PreconditionEntity?,
    name: String,
    isEnabled: Boolean,
    pipelineJson: String,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String,
    queueTimeoutMinutes: String,
    showNotificationOnFailure: Boolean,
    chainNodes: List<WorkflowNode>,
    linkedWorkflows: List<Workflow>,
    onConfirmSave: (PreconditionEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    PreconditionChangesConfirmDialog(
        precondition = precondition,
        name = name,
        isEnabled = isEnabled,
        onFailureAction = onFailureAction,
        maxRetries = maxRetries,
        retryIntervalSeconds = retryIntervalSeconds,
        retryStrategy = retryStrategy,
        queueTimeoutMinutes = queueTimeoutMinutes,
        showNotificationOnFailure = showNotificationOnFailure,
        chainNodes = chainNodes,
        linkedWorkflows = linkedWorkflows,
        onConfirm = {
            val entity =
                (
                    precondition ?: PreconditionEntity(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        type = PreconditionType.CHAINED_CHECK.name,
                    )
                ).copy(
                    name = name,
                    type = PreconditionType.CHAINED_CHECK.name,
                    isEnabled = isEnabled,
                    pipelineJson = pipelineJson,
                    onFailureAction = onFailureAction,
                    maxRetries = maxRetries.toIntOrNull() ?: 3,
                    retryIntervalSeconds = retryIntervalSeconds.toIntOrNull() ?: 30,
                    retryStrategy = retryStrategy,
                    queueTimeoutMinutes = queueTimeoutMinutes.toIntOrNull() ?: 10,
                    showNotificationOnFailure = showNotificationOnFailure,
                    updatedAt = System.currentTimeMillis(),
                )
            onConfirmSave(entity)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun PreconditionNodeConfigWrapper(
    node: WorkflowNode?,
    credentials: List<Credential>,
    onDismiss: () -> Unit,
    onSave: (WorkflowNode) -> Unit,
) {
    if (node == null) return
    NodeConfigBottomSheet(
        node = node,
        credentials = credentials,
        vaultPreconditions = emptyList(),
        showPreconditionsGuard = false,
        onDismiss = onDismiss,
        onSave = onSave,
    )
}

private fun findLinkedWorkflows(
    workflows: List<Workflow>,
    preconditionId: String?,
): List<Workflow> =
    if (preconditionId == null) {
        emptyList()
    } else {
        workflows.filter { wf ->
            wf.nodes.any { node -> node.preconditions.any { p -> p.id == preconditionId } }
        }
    }

private fun buildTestingEntity(
    preconditionId: String?,
    name: String,
    isEnabled: Boolean,
    pipelineJson: String,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String,
    queueTimeoutMinutes: String,
    showNotificationOnFailure: Boolean,
): PreconditionEntity =
    PreconditionEntity(
        id = preconditionId ?: UUID.randomUUID().toString(),
        name = name.ifBlank { "Precondition Pipeline" },
        type = PreconditionType.CHAINED_CHECK.name,
        isEnabled = isEnabled,
        pipelineJson = pipelineJson,
        onFailureAction = onFailureAction,
        maxRetries = maxRetries.toIntOrNull() ?: 3,
        retryIntervalSeconds = retryIntervalSeconds.toIntOrNull() ?: 30,
        retryStrategy = retryStrategy,
        queueTimeoutMinutes = queueTimeoutMinutes.toIntOrNull() ?: 10,
        showNotificationOnFailure = showNotificationOnFailure,
    )

private fun reorderChainNodesList(
    chainNodes: List<WorkflowNode>,
    fromIndex: Int,
    toIndex: Int,
): Pair<List<WorkflowNode>, List<WorkflowConnection>> {
    if (fromIndex !in chainNodes.indices || toIndex !in chainNodes.indices) {
        return chainNodes to emptyList()
    }
    val list = chainNodes.toMutableList()
    val item = list.removeAt(fromIndex)
    list.add(toIndex, item)
    val newConns =
        (0 until list.size - 1).map { i ->
            WorkflowConnection(fromNodeId = list[i].id, toNodeId = list[i + 1].id)
        }
    return list to newConns
}

@Composable
private fun AddChainNodeDialogWrapper(
    showDialog: Boolean,
    hasExistingNodes: Boolean,
    currentName: String,
    lastNodeId: String?,
    onDismiss: () -> Unit,
    onNodeAdded: (WorkflowNode, String, WorkflowConnection?) -> Unit,
) {
    if (!showDialog) return
    AddChainNodeDialog(
        title = if (!hasExistingNodes) "Select Precondition Check Type" else "Select Pipeline Step Node",
        isInitialPrecondition = !hasExistingNodes,
        onDismiss = onDismiss,
        onSelectType = { type ->
            val newNodeId = UUID.randomUUID().toString()
            val defaultConfig = WorkflowUiHelper.getDefaultConfigForType(type)
            val title = WorkflowUiHelper.getNodeTitle(type)
            val newNode =
                WorkflowNode(
                    id = newNodeId,
                    name = title,
                    type = type,
                    isEnabled = true,
                    configJson = defaultConfig,
                )
            val updatedName =
                if (currentName == "Precondition Guard" || currentName.isBlank()) {
                    "$title Guard"
                } else {
                    currentName
                }
            val conn = lastNodeId?.let { WorkflowConnection(fromNodeId = it, toNodeId = newNodeId) }
            onNodeAdded(newNode, updatedName, conn)
        },
    )
}
