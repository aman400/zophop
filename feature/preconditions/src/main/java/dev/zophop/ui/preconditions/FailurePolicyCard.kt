package dev.zophop.ui.preconditions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable Card composable for configuring Failure and Retry policies on Preconditions.
 */
@Composable
fun FailurePolicyCard(
    onFailureAction: String,
    onFailureActionChanged: (String) -> Unit,
    maxRetries: String,
    onMaxRetriesChanged: (String) -> Unit,
    retryIntervalSeconds: String,
    onRetryIntervalSecondsChanged: (String) -> Unit,
    retryStrategy: String = "FIXED",
    onRetryStrategyChanged: (String) -> Unit = {},
    queueTimeoutMinutes: String = "10",
    onQueueTimeoutMinutesChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Policy Configuration",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = onFailureAction == "FAIL_IMMEDIATELY",
                    onClick = { onFailureActionChanged("FAIL_IMMEDIATELY") },
                    label = { Text("Fail Immediately", fontSize = 12.sp) },
                    leadingIcon =
                        if (onFailureAction == "FAIL_IMMEDIATELY") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                )
                FilterChip(
                    selected = onFailureAction == "RETRY",
                    onClick = { onFailureActionChanged("RETRY") },
                    label = { Text("Wait & Retry", fontSize = 12.sp) },
                    leadingIcon =
                        if (onFailureAction == "RETRY") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                )
                FilterChip(
                    selected = onFailureAction == "QUEUE",
                    onClick = { onFailureActionChanged("QUEUE") },
                    label = { Text("Queue Mode", fontSize = 12.sp) },
                    leadingIcon =
                        if (onFailureAction == "QUEUE") {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else {
                            null
                        },
                )
            }

            if (onFailureAction == "QUEUE") {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Workflows will pause and remain in the execution queue until this precondition is satisfied, automatically resuming when ready.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = queueTimeoutMinutes,
                        onValueChange = onQueueTimeoutMinutesChanged,
                        label = { Text("Timeout (min)") },
                        placeholder = { Text("10") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = retryIntervalSeconds,
                        onValueChange = onRetryIntervalSecondsChanged,
                        label = { Text("Check Interval (sec)") },
                        placeholder = { Text("30") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
            }

            if (onFailureAction == "RETRY") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = maxRetries,
                        onValueChange = onMaxRetriesChanged,
                        label = { Text("Max Retries") },
                        placeholder = { Text("3") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = retryIntervalSeconds,
                        onValueChange = onRetryIntervalSecondsChanged,
                        label = { Text("Interval (sec)") },
                        placeholder = { Text("30") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                }
            }

            if (onFailureAction == "RETRY" || onFailureAction == "QUEUE") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (onFailureAction == "QUEUE") "Queue Polling Strategy" else "Retry Backoff Strategy",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = retryStrategy == "FIXED",
                            onClick = { onRetryStrategyChanged("FIXED") },
                            label = { Text("Fixed Backoff", fontSize = 12.sp) },
                            leadingIcon =
                                if (retryStrategy == "FIXED") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else {
                                    null
                                },
                        )
                        FilterChip(
                            selected = retryStrategy == "LINEAR",
                            onClick = { onRetryStrategyChanged("LINEAR") },
                            label = { Text("Linear Backoff", fontSize = 12.sp) },
                            leadingIcon =
                                if (retryStrategy == "LINEAR") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else {
                                    null
                                },
                        )
                        FilterChip(
                            selected = retryStrategy == "EXPONENTIAL",
                            onClick = { onRetryStrategyChanged("EXPONENTIAL") },
                            label = { Text("Exponential Backoff", fontSize = 12.sp) },
                            leadingIcon =
                                if (retryStrategy == "EXPONENTIAL") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else {
                                    null
                                },
                        )
                        FilterChip(
                            selected = retryStrategy == "RANDOMIZED",
                            onClick = { onRetryStrategyChanged("RANDOMIZED") },
                            label = { Text("Randomized Backoff", fontSize = 12.sp) },
                            leadingIcon =
                                if (retryStrategy == "RANDOMIZED") {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else {
                                    null
                                },
                        )
                    }
                    val strategyDesc =
                        when (retryStrategy) {
                            "EXPONENTIAL" -> "Interval doubles with each attempt (${retryIntervalSeconds}s, ${retryIntervalSeconds.toIntOrNull()?.times(2) ?: "2x"}s, ...)."
                            "LINEAR" -> "Interval scales linearly with each attempt (${retryIntervalSeconds}s, ${retryIntervalSeconds.toIntOrNull()?.times(2) ?: "2x"}s, ${retryIntervalSeconds.toIntOrNull()?.times(3) ?: "3x"}s, ...)."
                            "RANDOMIZED" -> "Applies randomized jitter up to exponential ceiling to avoid collisions."
                            else -> "Constant wait interval (${retryIntervalSeconds}s) before every check attempt."
                        }
                    Text(
                        text = strategyDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
