package dev.zophop.ui.preconditions

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.model.PreconditionType
import dev.zophop.ui.workflows.NodeExecutionStepper
import dev.zophop.ui.workflows.WorkflowUiHelper

/**
 * Compact Modernized Card Item for a Precondition Rule in the Vault list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PreconditionCardItem(
    precondition: PreconditionEntity,
    linkedWorkflowCount: Int = 0,
    onTestPreconditionWithProgress: ((PreconditionEntity, (List<NodeExecutionStep>) -> Unit, (PreconditionResult) -> Unit) -> Unit)? = null,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<PreconditionResult?>(null) }
    var testSteps by remember { mutableStateOf<List<NodeExecutionStep>>(emptyList()) }
    var showTestStepperSheet by remember { mutableStateOf(false) }

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

    val (iconVector, typeTitle) =
        remember(precondition.type, precondition.pipelineJson) {
            WorkflowUiHelper.getPreconditionPipelineSummary(precondition.type, precondition.pipelineJson)
        }

    val containerColor by animateColorAsState(
        targetValue = if (precondition.isEnabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(durationMillis = 220),
        label = "PreconditionCardColor",
    )

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
            shape = RoundedCornerShape(14.dp),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = containerColor,
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(12.dp)
                        .animateContentSize(animationSpec = tween(220)),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Header Row: Icon, Title, Status, Switch
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
                                    imageVector = iconVector,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = precondition.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )

                                testResult?.let { res ->
                                    val isPassed = res is PreconditionResult.Passed
                                    val badgeText = if (isPassed) "Verified" else "Failed"
                                    val badgeIcon = if (isPassed) Icons.Default.CheckCircle else Icons.Default.Error
                                    val bg = if (isPassed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                                    val fg = if (isPassed) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                                    SuggestionChip(
                                        onClick = { showTestStepperSheet = true },
                                        label = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            ) {
                                                Icon(imageVector = badgeIcon, contentDescription = null, modifier = Modifier.size(10.dp))
                                                Text(
                                                    text = badgeText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        },
                                        colors =
                                            SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = bg,
                                                labelColor = fg,
                                            ),
                                        modifier = Modifier.height(20.dp),
                                    )
                                }
                            }

                            Text(
                                text = typeTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Switch(
                        checked = precondition.isEnabled,
                        onCheckedChange = { targetEnabled ->
                            onToggle(targetEnabled)
                        },
                        modifier = Modifier.height(28.dp),
                    )
                }

                if (precondition.type == PreconditionType.HEALTH_CHECK.name && precondition.targetUrl.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "${precondition.method} → ${precondition.targetUrl}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Compact Metadata Badges
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val isRetry = precondition.onFailureAction.equals("RETRY", ignoreCase = true)
                    if (isRetry) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(11.dp))
                                Text(
                                    text = "Retry ${precondition.maxRetries}x (${precondition.retryIntervalSeconds}s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    if (precondition.showNotificationOnFailure) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(11.dp))
                                Text(
                                    "System Alert",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    Surface(
                        color =
                            if (linkedWorkflowCount >
                                0
                            ) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        contentColor =
                            if (linkedWorkflowCount >
                                0
                            ) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(11.dp))
                            Text(
                                text =
                                    if (linkedWorkflowCount >
                                        0
                                    ) {
                                        "$linkedWorkflowCount Workflow${if (linkedWorkflowCount > 1) "s" else ""}"
                                    } else {
                                        "No Workflows"
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                // Bottom Action Bar: Test / Viewing Button & Edit / Delete Icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isTesting) {
                                showTestStepperSheet = true
                            } else {
                                isTesting = true
                                showTestStepperSheet = true
                                testResult = null
                                testSteps = emptyList()
                                onTestPreconditionWithProgress?.invoke(
                                    precondition,
                                    { steps -> testSteps = steps },
                                    { res ->
                                        isTesting = false
                                        testResult = res
                                    },
                                )
                            }
                        },
                        modifier = Modifier.height(26.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Verifying...", fontSize = 11.sp)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("Test", fontSize = 11.sp)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(28.dp),
                        ) {
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

        if (showTestStepperSheet) {
            PreconditionExecutionStepperBottomSheet(
                preconditionName = precondition.name,
                steps = testSteps,
                isTesting = isTesting,
                onDismiss = { showTestStepperSheet = false },
            )
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Precondition Execution Stepper",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = preconditionName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .padding(end = 8.dp)
                                    .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        if (onCancelExecution != null) {
                            OutlinedButton(
                                onClick = onCancelExecution,
                                modifier = Modifier.padding(end = 8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Cancel", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
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
                        Text("Verifying precondition rule...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    NodeExecutionStepper(steps = steps)
                }
            }
        }
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
}
