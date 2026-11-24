package dev.zophop.ui.preconditions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.toDomain
import dev.zophop.domain.model.Precondition
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode

@Composable
fun PreconditionChangesConfirmDialog(
    precondition: PreconditionEntity?,
    name: String,
    isEnabled: Boolean,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String = "FIXED",
    queueTimeoutMinutes: String = "10",
    showNotificationOnFailure: Boolean,
    chainNodes: List<WorkflowNode>,
    linkedWorkflows: List<Workflow>,
    icon: String = "",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val nameChanged = name != (precondition?.name ?: "")
    val iconChanged = icon != (precondition?.icon ?: "")
    val enabledChanged = isEnabled != (precondition?.isEnabled ?: true)
    val retryPolicyChanged =
        onFailureAction != (precondition?.onFailureAction ?: "FAIL_IMMEDIATELY") ||
            maxRetries != (precondition?.maxRetries?.toString() ?: "3") ||
            retryIntervalSeconds != (precondition?.retryIntervalSeconds?.toString() ?: "30") ||
            retryStrategy != (precondition?.retryStrategy ?: "FIXED") ||
            queueTimeoutMinutes != (precondition?.queueTimeoutMinutes?.toString() ?: "10") ||
            showNotificationOnFailure != (precondition?.showNotificationOnFailure ?: false)
    val originalChainIds = (precondition?.toDomain() as? Precondition.ChainCheck)?.nodes?.map { it.id }?.toSet() ?: emptySet()
    val currentChainIds = chainNodes.map { it.id }.toSet()
    val addedSteps = chainNodes.filter { !originalChainIds.contains(it.id) }
    val removedSteps =
        (precondition?.toDomain() as? Precondition.ChainCheck)?.nodes?.filter { !currentChainIds.contains(it.id) } ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Precondition Updates") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (linkedWorkflows.isNotEmpty()) {
                    LinkedWorkflowsWarningCard(linkedWorkflows)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                PreconditionChangesSummaryList(
                    precondition = precondition,
                    name = name,
                    nameChanged = nameChanged,
                    icon = icon,
                    iconChanged = iconChanged,
                    isEnabled = isEnabled,
                    enabledChanged = enabledChanged,
                    retryPolicyChanged = retryPolicyChanged,
                    onFailureAction = onFailureAction,
                    maxRetries = maxRetries,
                    retryIntervalSeconds = retryIntervalSeconds,
                    retryStrategy = retryStrategy,
                    queueTimeoutMinutes = queueTimeoutMinutes,
                    showNotificationOnFailure = showNotificationOnFailure,
                    addedSteps = addedSteps,
                    removedSteps = removedSteps,
                )
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
private fun LinkedWorkflowsWarningCard(linkedWorkflows: List<Workflow>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "⚠️ WARNING: Precondition In Use!",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "This precondition is currently attached to ${linkedWorkflows.size} workflow(s):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            linkedWorkflows.forEach { wf ->
                Text(
                    "• ${wf.name}",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                )
            }
            Text(
                text = "Saving these changes will immediately modify execution behavior across these workflows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun PreconditionChangesSummaryList(
    precondition: PreconditionEntity?,
    name: String,
    nameChanged: Boolean,
    icon: String,
    iconChanged: Boolean,
    isEnabled: Boolean,
    enabledChanged: Boolean,
    retryPolicyChanged: Boolean,
    onFailureAction: String,
    maxRetries: String,
    retryIntervalSeconds: String,
    retryStrategy: String,
    queueTimeoutMinutes: String,
    showNotificationOnFailure: Boolean,
    addedSteps: List<WorkflowNode>,
    removedSteps: List<WorkflowNode>,
) {
    Text("Here is a summary of new changes to be saved:")
    if (iconChanged) {
        val origIcon = precondition?.icon.orEmpty()
        Text(
            "• Icon: ${if (origIcon.isNotBlank()) origIcon else "(None)"} → ${if (icon.isNotBlank()) icon else "(None)"}",
            fontWeight = FontWeight.Bold,
        )
    }
    if (nameChanged) {
        Text("• Name: '${precondition?.name ?: ""}' → '$name'", fontWeight = FontWeight.Bold)
    }
    if (enabledChanged) {
        Text("• Enabled: $isEnabled")
    }
    if (retryPolicyChanged) {
        Text("• Failure & Retry Policy updated:", fontWeight = FontWeight.Bold)
        Text("  - Action: $onFailureAction (was ${precondition?.onFailureAction ?: "FAIL_IMMEDIATELY"})")
        if (onFailureAction == "QUEUE") {
            Text("  - Queue Timeout: ${queueTimeoutMinutes}m (was ${precondition?.queueTimeoutMinutes ?: 10}m)")
        } else if (onFailureAction == "RETRY") {
            Text("  - Max Retries: $maxRetries (was ${precondition?.maxRetries ?: 3})")
            Text("  - Retry Interval: ${retryIntervalSeconds}s (was ${precondition?.retryIntervalSeconds ?: 30}s)")
            Text("  - Backoff Strategy: $retryStrategy (was ${precondition?.retryStrategy ?: "FIXED"})")
        }
        Text("  - Notification Alert: $showNotificationOnFailure (was ${precondition?.showNotificationOnFailure ?: false})")
    }
    if (addedSteps.isNotEmpty()) {
        Text(
            "• Added Pipeline Steps (${addedSteps.size}):",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        addedSteps.forEach {
            Text("  + ${it.name} [${it.type}]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
    if (removedSteps.isNotEmpty()) {
        Text(
            "• Removed Pipeline Steps (${removedSteps.size}):",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        removedSteps.forEach {
            Text("  - ${it.name} [${it.type}]", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
}
