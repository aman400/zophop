package dev.zophop.ui.workflows

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.NodeType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorkflowExecutionCard(
    log: WorkflowExecutionLogEntity,
    isInitiallyExpanded: Boolean = false,
    onRetryWorkflowFromFailedNode: ((failedNodeId: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(isInitiallyExpanded) }

    val isSuccess = log.status == "SUCCESS"
    val steps =
        remember(log.logs) {
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
        }

    val totalDurationMs =
        remember(steps) {
            val sum = steps.mapNotNull { it.durationMs }.sum()
            if (sum > 0) sum else null
        }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(12.dp)
                    .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkflowExecutionCardHeader(
                status = log.status,
                timestamp = log.timestamp,
                totalDurationMs = totalDurationMs,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
            )

            WorkflowExecutionIdentifiersRow(
                workflowId = log.workflowId,
                executionId = log.id,
                onCopy = { label, text -> copyToClipboard(context, label, text) },
            )

            val errorReason = log.errorReason
            if (!errorReason.isNullOrBlank() && log.status != "SUCCESS") {
                ExecutionErrorBanner(errorReason = errorReason)
            }

            AnimatedVisibility(visible = expanded) {
                Box(modifier = Modifier.padding(top = 4.dp)) {
                    NodeExecutionStepper(
                        steps = steps,
                        onRetryFromNode = onRetryWorkflowFromFailedNode,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowExecutionCardHeader(
    status: String,
    timestamp: Long,
    totalDurationMs: Long?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExecutionStatusBadge(status = status)

            val formattedTime =
                remember(timestamp) {
                    SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                }
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
                    text = formattedTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (totalDurationMs != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = WorkflowUiHelper.formatDuration(totalDurationMs),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorkflowExecutionIdentifiersRow(
    workflowId: String,
    executionId: String,
    onCopy: (String, String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.clickable { onCopy("Workflow ID", workflowId) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "WF: $workflowId",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Workflow ID",
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.clickable { onCopy("Execution ID", executionId) },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(11.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Exec: $executionId",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy Execution ID",
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun ExecutionStatusBadge(status: String) {
    val isSuccess = status == "SUCCESS"
    val isQueued = status == "QUEUED"
    val isCancelled = status == "CANCELLED"

    val (bg, fg, icon) =
        when {
            isSuccess -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Default.CheckCircle)
            isQueued -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, Icons.Default.HourglassTop)
            isCancelled -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.Cancel)
            else -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, Icons.Default.Error)
        }

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = fg)
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = fg,
            )
        }
    }
}

@Composable
private fun ExecutionErrorBanner(errorReason: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = errorReason,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
}
