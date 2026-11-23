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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
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
    var selectedErrorLog by remember { mutableStateOf<String?>(null) }

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
                onShowErrorLog = { selectedErrorLog = it },
            )
        }
    }

    selectedErrorLog?.let { log ->
        NodeExecutionErrorDialog(
            log = log,
            onDismiss = { selectedErrorLog = null },
        )
    }
}

@Composable
private fun NodeExecutionStepRow(
    step: NodeExecutionStep,
    isFirst: Boolean,
    isLast: Boolean,
    onRetryFromNode: ((nodeId: String) -> Unit)?,
    onShowErrorLog: (String) -> Unit,
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
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .padding(
                            top = if (isFirst) 18.dp else 0.dp,
                            bottom = if (isLast) 18.dp else 0.dp,
                        ).fillMaxHeight()
                        .background(lineColor),
            )

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
                    .fillMaxWidth()
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
                        .padding(12.dp)
                        .animateContentSize(animationSpec = tween(250)),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            color =
                                if (step.state ==
                                    NodeStepState.SKIPPED
                                ) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                        )
                    }

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

                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        colors =
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor = statusBg,
                                labelColor = statusFg,
                            ),
                    )
                }

                Text(
                    text = WorkflowUiHelper.getNodeTitle(step.nodeType),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (step.logMessage.isNotBlank()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    shape = RoundedCornerShape(6.dp),
                                ).clickable { onShowErrorLog(step.logMessage) }
                                .padding(8.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = step.logMessage,
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
                            Text(
                                text = "Tap to view complete log",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                if (step.state == NodeStepState.FAILED && onRetryFromNode != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { onRetryFromNode(step.nodeId) },
                        modifier = Modifier.align(Alignment.End).height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry From This Step", fontSize = 11.sp)
                    }
                }
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
                .size(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = CircleShape,
                ),
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

@Composable
private fun NodeExecutionErrorDialog(
    log: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Complete Execution Log") },
        text = {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(8.dp),
                        ).padding(12.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = log,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
