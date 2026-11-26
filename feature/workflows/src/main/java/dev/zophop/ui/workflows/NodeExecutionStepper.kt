package dev.zophop.ui.workflows

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState

@Composable
fun NodeExecutionStepper(
    steps: List<NodeExecutionStep>,
    onRetryFromNode: ((nodeId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var selectedStepForLog by remember { mutableStateOf<NodeExecutionStep?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        steps.forEachIndexed { index, step ->
            NodeExecutionStepRow(
                step = step,
                isFirst = index == 0,
                isLast = index == steps.size - 1,
                onRetryFromNode = onRetryFromNode,
                onShowCompleteLog = { selectedStepForLog = it },
            )
        }
    }

    selectedStepForLog?.let { step ->
        CompleteExecutionLogDialog(
            step = step,
            onDismiss = { selectedStepForLog = null },
        )
    }
}

@Composable
private fun NodeExecutionStepRow(
    step: NodeExecutionStep,
    isFirst: Boolean,
    isLast: Boolean,
    onRetryFromNode: ((nodeId: String) -> Unit)?,
    onShowCompleteLog: (NodeExecutionStep) -> Unit,
) {
    val isRetryStep = (
        step.nodeId.contains("retry", ignoreCase = true) ||
            step.nodeName.contains("Retry", ignoreCase = true) ||
            step.logMessage.contains("retry", ignoreCase = true)
    )

    val secRemaining = Regex("Waiting (\\d+)s remaining").find(step.logMessage)?.groupValues?.getOrNull(1)

    val lineColor =
        when {
            step.state == NodeStepState.SUCCESS -> MaterialTheme.colorScheme.primary
            step.state == NodeStepState.FAILED -> MaterialTheme.colorScheme.error
            isRetryStep || step.state == NodeStepState.EXECUTING -> MaterialTheme.colorScheme.primary
            step.state == NodeStepState.SKIPPED -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier =
                Modifier
                    .width(36.dp)
                    .fillMaxHeight(),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (!isFirst) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(3.dp)
                            .height(8.dp)
                            .background(lineColor),
                )
            }

            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxHeight()
                            .padding(top = 32.dp)
                            .width(3.dp)
                            .background(lineColor),
                )
            }

            NodeStepDotIndicator(
                step = step,
                isRetryStep = isRetryStep,
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        val infiniteTransition = rememberInfiniteTransition(label = "ExecutingPulse")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.35f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(750),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "PulseAlpha",
        )

        OutlinedCard(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(bottom = 12.dp),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor =
                        when {
                            step.state == NodeStepState.EXECUTING ->
                                MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = pulseAlpha,
                                )
                            step.state == NodeStepState.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                            step.state == NodeStepState.SKIPPED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                            else -> MaterialTheme.colorScheme.surface
                        },
                ),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .animateContentSize(animationSpec = tween(250)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                        Icon(
                            imageVector = WorkflowUiHelper.getNodeIcon(step.nodeType),
                            contentDescription = null,
                            tint =
                                when {
                                    step.state == NodeStepState.SUCCESS || step.state == NodeStepState.EXECUTING -> MaterialTheme.colorScheme.primary
                                    step.state == NodeStepState.FAILED -> MaterialTheme.colorScheme.error
                                    step.state == NodeStepState.SKIPPED -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = step.nodeName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color =
                                if (step.state == NodeStepState.SKIPPED) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }

                    NodeStepStatusChip(
                        step = step,
                        isRetryStep = isRetryStep,
                        secRemaining = secRemaining,
                    )
                }

                Text(
                    text = WorkflowUiHelper.getNodeTitle(step.nodeType),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                NodeStepTimingRow(step = step)

                NodeStepLogBox(step = step, onShowCompleteLog = onShowCompleteLog)

                NodeStepActionRow(step = step, onRetryFromNode = onRetryFromNode)
            }
        }
    }
}

@Composable
private fun NodeStepTimingRow(step: NodeExecutionStep) {
    val timeStr = WorkflowUiHelper.formatExecutionTime(step.executedAt)
    val durationStr = WorkflowUiHelper.formatDuration(step.durationMs)
    if (timeStr.isNotBlank() || durationStr.isNotBlank()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (timeStr.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (durationStr.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = durationStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeStepStatusChip(
    step: NodeExecutionStep,
    isRetryStep: Boolean,
    secRemaining: String?,
) {
    val (statusText, statusBg, statusFg) =
        when {
            isRetryStep -> {
                val label = if (secRemaining != null) "Waiting (${secRemaining}s)" else "Waiting"
                Triple(
                    label,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            step.state == NodeStepState.EXECUTING ->
                Triple(
                    "In Progress...",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            step.state == NodeStepState.PENDING ->
                Triple(
                    "Pending",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            step.state == NodeStepState.SUCCESS ->
                Triple(
                    "Success",
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            step.state == NodeStepState.FAILED ->
                Triple(
                    "Failed",
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
            step.state == NodeStepState.SKIPPED ->
                Triple(
                    "Skipped",
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            else ->
                Triple(
                    "Pending",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

    Surface(
        color = statusBg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = statusText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = statusFg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun NodeStepLogBox(
    step: NodeExecutionStep,
    onShowCompleteLog: (NodeExecutionStep) -> Unit,
) {
    if (step.logMessage.isNotBlank() || !step.errorReason.isNullOrBlank()) {
        val displaySummary = step.logMessage.ifBlank { step.errorReason.orEmpty() }
        val sectionCount = remember(step.logMessage) { parseStructuredLogSections(step.logMessage).size }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                    ).clickable { onShowCompleteLog(step) }
                    .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = displaySummary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        when {
                            step.state == NodeStepState.FAILED -> MaterialTheme.colorScheme.error
                            step.state == NodeStepState.EXECUTING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Tap to view complete log",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (sectionCount > 1) {
                            Text(
                                text = "$sectionCount sections",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.OpenInFull,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeStepActionRow(
    step: NodeExecutionStep,
    onRetryFromNode: ((nodeId: String) -> Unit)?,
) {
    if (step.state == NodeStepState.FAILED) {
        if (!step.retriedInExecutionId.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "Retried in ${step.retriedInExecutionId}",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        } else if (onRetryFromNode != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { onRetryFromNode(step.nodeId) },
                modifier = Modifier.heightIn(min = 32.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Retry From This Step",
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NodeStepDotIndicator(
    step: NodeExecutionStep,
    isRetryStep: Boolean,
) {
    Box(
        modifier =
            Modifier
                .padding(top = 8.dp)
                .size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isRetryStep && step.state == NodeStepState.EXECUTING -> {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Waiting to Retry",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            isRetryStep -> {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = "Retry Step",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            step.state == NodeStepState.EXECUTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            step.state == NodeStepState.SUCCESS -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            step.state == NodeStepState.FAILED -> {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            step.state == NodeStepState.SKIPPED -> {
                Icon(
                    imageVector = Icons.Default.RemoveCircleOutline,
                    contentDescription = "Skipped",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            else -> {
                Box(
                    modifier =
                        Modifier
                            .size(16.dp)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                )
            }
        }
    }
}
