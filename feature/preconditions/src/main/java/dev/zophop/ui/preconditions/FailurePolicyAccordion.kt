package dev.zophop.ui.preconditions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FailurePolicyAccordion(
    onFailureAction: String,
    onFailureActionChanged: (String) -> Unit,
    maxRetries: String,
    onMaxRetriesChanged: (String) -> Unit,
    retryIntervalSeconds: String,
    onRetryIntervalSecondsChanged: (String) -> Unit,
    showNotificationOnFailure: Boolean,
    onShowNotificationOnFailureChanged: (Boolean) -> Unit,
    onShowNotificationRationale: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column {
                        Text(
                            text = "Failure & Retry Policy",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        val actionDesc = if (onFailureAction == "RETRY") "Wait & Retry ($maxRetries attempts)" else "Fail Immediately"
                        Text(
                            text = if (expanded) "Configure retry attempts and failure response" else actionDesc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                FailurePolicyCard(
                    onFailureAction = onFailureAction,
                    onFailureActionChanged = onFailureActionChanged,
                    maxRetries = maxRetries,
                    onMaxRetriesChanged = onMaxRetriesChanged,
                    retryIntervalSeconds = retryIntervalSeconds,
                    onRetryIntervalSecondsChanged = onRetryIntervalSecondsChanged,
                    showNotificationOnFailure = showNotificationOnFailure,
                    onShowNotificationOnFailureChanged = onShowNotificationOnFailureChanged,
                    onShowNotificationRationale = onShowNotificationRationale,
                )
            }
        }
    }
}
