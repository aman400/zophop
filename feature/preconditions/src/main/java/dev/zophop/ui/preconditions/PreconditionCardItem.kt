package dev.zophop.ui.preconditions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.CancelExecutionConfirmDialog
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.engine.markStepsAsCancelled
import dev.zophop.domain.model.PreconditionType
import dev.zophop.ui.workflows.NodeExecutionStepper
import dev.zophop.ui.workflows.WorkflowUiHelper

/**
 * Compact Modernized Card Item for a Precondition Rule in the Vault list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreconditionCardItem(
    precondition: PreconditionEntity,
    linkedWorkflowCount: Int = 0,
    onTestPreconditionWithProgress: ((PreconditionEntity, (List<NodeExecutionStep>) -> Unit, (PreconditionResult) -> Unit) -> Unit)? = null,
    onCancelTestPrecondition: ((String) -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<PreconditionResult?>(null) }
    var testSteps by remember { mutableStateOf<List<NodeExecutionStep>>(emptyList()) }
    var showTestStepperSheet by remember { mutableStateOf(false) }
    var showCancelConfirmDialog by remember { mutableStateOf(false) }
    var activeTestToken by remember { mutableStateOf(0L) }

    LaunchedEffect(isTesting) {
        if (!isTesting) {
            showCancelConfirmDialog = false
        }
    }

    val cancelOngoingTest: () -> Unit = {
        activeTestToken += 1L
        isTesting = false
        val cancelledSteps = buildCancelledTestSteps(precondition, testSteps)
        testSteps = cancelledSteps
        testResult =
            PreconditionResult.Failed(
                reason = PRECONDITION_TEST_CANCELLED_REASON,
                steps = cancelledSteps,
            )
        onCancelTestPrecondition?.invoke(precondition.id)
    }

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

    val (iconVector, typeTitle) =
        remember(precondition.type, precondition.pipelineJson) {
            WorkflowUiHelper.getPreconditionPipelineSummary(precondition.type, precondition.pipelineJson)
        }

    val containerColor by animateColorAsState(
        targetValue = if (precondition.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(durationMillis = 220),
        label = "PreconditionCardColor",
    )

    val borderColor =
        if (precondition.isEnabled) {
            MaterialTheme.colorScheme.outlineVariant
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            PreconditionDismissBackground(dismissState)
        },
    ) {
        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, borderColor),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = containerColor,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(14.dp)
                        .animateContentSize(animationSpec = tween(220)),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PreconditionCardHeader(
                    precondition = precondition,
                    iconVector = iconVector,
                    typeTitle = typeTitle,
                    testResult = testResult,
                    onStatusChipClick = { showTestStepperSheet = true },
                    onToggle = onToggle,
                )

                if (precondition.type == PreconditionType.HEALTH_CHECK.name && precondition.targetUrl.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${precondition.method} → ${precondition.targetUrl}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                PreconditionMetadataBadges(
                    precondition = precondition,
                    linkedWorkflowCount = linkedWorkflowCount,
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )

                PreconditionCardBottomActions(
                    isTesting = isTesting,
                    onTestOrOpenSheet = {
                        if (isTesting) {
                            showTestStepperSheet = true
                        } else {
                            val runToken = activeTestToken + 1L
                            activeTestToken = runToken
                            isTesting = true
                            showTestStepperSheet = true
                            testResult = null
                            testSteps = emptyList()
                            onTestPreconditionWithProgress?.invoke(
                                precondition,
                                { steps ->
                                    if (activeTestToken == runToken && isTesting) {
                                        testSteps = steps
                                    }
                                },
                                { res ->
                                    if (activeTestToken == runToken) {
                                        isTesting = false
                                        testResult = res
                                        val resSteps =
                                            when (res) {
                                                is PreconditionResult.Passed -> res.steps
                                                is PreconditionResult.Failed -> res.steps
                                            }
                                        if (resSteps.isNotEmpty()) {
                                            testSteps = resSteps
                                        }
                                    }
                                },
                            )
                        }
                    },
                    onCancelTest = { showCancelConfirmDialog = true },
                    onEdit = onEdit,
                    onDelete = onDelete,
                )
            }
        }

        if (showCancelConfirmDialog && isTesting) {
            CancelExecutionConfirmDialog(
                targetName = precondition.name,
                onConfirm = cancelOngoingTest,
                onDismiss = { showCancelConfirmDialog = false },
            )
        }

        if (showTestStepperSheet) {
            PreconditionExecutionStepperBottomSheet(
                preconditionName = precondition.name,
                steps = testSteps,
                isTesting = isTesting,
                onCancelExecution = cancelOngoingTest,
                onDismiss = { showTestStepperSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreconditionCardHeader(
    precondition: PreconditionEntity,
    iconVector: ImageVector,
    typeTitle: String,
    testResult: PreconditionResult?,
    onStatusChipClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color =
                    if (precondition.isEnabled) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (precondition.icon.isNotBlank()) {
                        Text(
                            text = precondition.icon,
                            fontSize = 18.sp,
                        )
                    } else {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint =
                                if (precondition.isEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = precondition.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (precondition.isEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    testResult?.let { res ->
                        PreconditionTestStatusChip(
                            testResult = res,
                            onClick = onStatusChipClick,
                        )
                    }
                }

                Text(
                    text = typeTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Switch(
            checked = precondition.isEnabled,
            onCheckedChange = onToggle,
        )
    }
}

private const val PRECONDITION_TEST_CANCELLED_REASON = "Precondition test cancelled by user"

private fun buildCancelledTestSteps(
    precondition: PreconditionEntity,
    currentSteps: List<NodeExecutionStep>,
): List<NodeExecutionStep> {
    if (currentSteps.isNotEmpty()) {
        return currentSteps.markStepsAsCancelled(PRECONDITION_TEST_CANCELLED_REASON)
    }
    return listOf(
        NodeExecutionStep(
            nodeId = precondition.id.ifBlank { "precondition_cancelled" },
            nodeName = precondition.name.ifBlank { "Precondition Check" },
            nodeType = precondition.type,
            state = NodeStepState.FAILED,
            logMessage = PRECONDITION_TEST_CANCELLED_REASON,
            errorReason = "Cancelled",
            executedAt = System.currentTimeMillis(),
            durationMs = 0L,
        ),
    )
}

@Composable
private fun PreconditionTestStatusChip(
    testResult: PreconditionResult,
    onClick: () -> Unit,
) {
    val isPassed = testResult is PreconditionResult.Passed
    val isCancelled =
        testResult is PreconditionResult.Failed &&
            testResult.reason.contains("cancelled", ignoreCase = true)
    val badgeText =
        when {
            isPassed -> "Verified"
            isCancelled -> "Cancelled"
            else -> "Failed"
        }
    val badgeIcon =
        when {
            isPassed -> Icons.Default.CheckCircle
            isCancelled -> Icons.Default.Close
            else -> Icons.Default.Error
        }
    val bg =
        when {
            isPassed -> MaterialTheme.colorScheme.primaryContainer
            isCancelled -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.errorContainer
        }
    val fg =
        when {
            isPassed -> MaterialTheme.colorScheme.onPrimaryContainer
            isCancelled -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onErrorContainer
        }

    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(0.5.dp, fg.copy(alpha = 0.25f)),
        modifier =
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = badgeIcon,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
            )
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreconditionMetadataBadges(
    precondition: PreconditionEntity,
    linkedWorkflowCount: Int,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val isRetry = precondition.onFailureAction.equals("RETRY", ignoreCase = true)
        val isQueue = precondition.onFailureAction.equals("QUEUE", ignoreCase = true)
        if (isQueue) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.HourglassTop, contentDescription = null, modifier = Modifier.size(12.dp))
                    Text(
                        text = "Queue Mode (${precondition.queueTimeoutMinutes}m)",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        } else if (isRetry) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(12.dp))
                    val strategySuffix =
                        when (precondition.retryStrategy.uppercase()) {
                            "EXPONENTIAL" -> ", Exp"
                            "RANDOMIZED" -> ", Rand"
                            else -> ""
                        }
                    Text(
                        text = "Retry ${precondition.maxRetries}x (${precondition.retryIntervalSeconds}s$strategySuffix)",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Surface(
            color =
                if (linkedWorkflowCount > 0) {
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            contentColor =
                if (linkedWorkflowCount > 0) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(12.dp))
                Text(
                    text =
                        if (linkedWorkflowCount > 0) {
                            "$linkedWorkflowCount Workflow${if (linkedWorkflowCount > 1) "s" else ""}"
                        } else {
                            "No Workflows"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (!precondition.isEnabled) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = "Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreconditionCardBottomActions(
    isTesting: Boolean,
    onTestOrOpenSheet: () -> Unit,
    onCancelTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onTestOrOpenSheet,
                modifier = Modifier.heightIn(min = 34.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Verifying...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                } else {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Test",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            if (isTesting) {
                OutlinedButton(
                    onClick = onCancelTest,
                    modifier = Modifier.heightIn(min = 34.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Cancel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreconditionExecutionStepperBottomSheet(
    preconditionName: String,
    steps: List<NodeExecutionStep>,
    isTesting: Boolean,
    onCancelExecution: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isTesting) {
        if (!isTesting) {
            showCancelConfirmDialog = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(steps.size, steps.map { "${it.nodeId}_${it.state}" }) {
        if (steps.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    NoSwipeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Precondition Execution Stepper",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = preconditionName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        if (onCancelExecution != null) {
                            OutlinedButton(
                                onClick = { showCancelConfirmDialog = true },
                                modifier = Modifier.heightIn(min = 32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "Cancel",
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (steps.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (isTesting) "Verifying precondition rule..." else "Precondition verification stopped.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    NodeExecutionStepper(steps = steps)
                }
            }
        }
    }

    if (showCancelConfirmDialog && isTesting && onCancelExecution != null) {
        CancelExecutionConfirmDialog(
            targetName = preconditionName,
            onConfirm = onCancelExecution,
            onDismiss = { showCancelConfirmDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreconditionDismissBackground(dismissState: SwipeToDismissBoxState) {
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
        if (direction == SwipeToDismissBoxValue.StartToEnd) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(color, shape = RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        if (direction != SwipeToDismissBoxValue.Settled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                Text(text = text, color = tint, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}
