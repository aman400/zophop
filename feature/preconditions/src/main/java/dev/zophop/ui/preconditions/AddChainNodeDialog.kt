package dev.zophop.ui.preconditions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.engine.plugin.UnifiedPluginRegistry
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest
import dev.zophop.ui.workflows.WorkflowUiHelper

@Composable
fun AddChainNodeDialog(
    title: String = "Select Pipeline Step Node",
    isInitialPrecondition: Boolean = false,
    onDismiss: () -> Unit,
    onSelectType: (String) -> Unit,
) {
    val context = LocalContext.current
    val unifiedRegistry = remember { UnifiedPluginRegistry(context) }
    var manifests by remember { mutableStateOf(BuiltInPluginRegistry.getBuiltInManifests()) }

    LaunchedEffect(Unit) {
        val all = unifiedRegistry.getAllManifests()
        if (all.isNotEmpty()) {
            manifests = all
        }
    }

    val conditionCheckNodes =
        remember(manifests) {
            manifests.filter {
                it.category == NodeCategory.LOGIC &&
                    (it.typeId.contains("CHECK", ignoreCase = true) || it.displayName.contains("Check", ignoreCase = true))
            }
        }

    val httpNodes =
        remember(manifests) {
            manifests.filter {
                it.typeId.contains("HTTP_ACTION", ignoreCase = true) ||
                    it.typeId.equals("HTTP", ignoreCase = true)
            }
        }

    val logicNodes =
        remember(manifests) {
            manifests.filter {
                it.category == NodeCategory.LOGIC &&
                    !it.typeId.contains("CHECK", ignoreCase = true) &&
                    !it.displayName.contains("Check", ignoreCase = true)
            }
        }

    val actionNodes =
        remember(manifests) {
            manifests.filter { it.category == NodeCategory.ACTION }
        }

    val extractionNodes =
        remember(manifests) {
            manifests.filter { it.category == NodeCategory.EXTRACTION }
        }

    NoSwipeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isInitialPrecondition) {
                    if (conditionCheckNodes.isNotEmpty()) {
                        Text(
                            text = "System & Hardware Checks",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        conditionCheckNodes.forEach { manifest ->
                            NodeSelectionCard(manifest = manifest, onClick = { onSelectType(manifest.typeId) })
                        }
                    }

                    if (httpNodes.isNotEmpty()) {
                        Text(
                            text = "Network & HTTP/HTTPS Checks",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        httpNodes.forEach { manifest ->
                            NodeSelectionCard(manifest = manifest, onClick = { onSelectType(manifest.typeId) })
                        }
                    }
                } else {
                    if (conditionCheckNodes.isNotEmpty()) {
                        Text(
                            text = "System & Hardware Checks",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        conditionCheckNodes.forEach { manifest ->
                            NodeSelectionCard(manifest = manifest, onClick = { onSelectType(manifest.typeId) })
                        }
                    }

                    if (logicNodes.isNotEmpty()) {
                        Text(
                            text = "Logic & Filtering",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        logicNodes.forEach { manifest ->
                            NodeSelectionCard(manifest = manifest, onClick = { onSelectType(manifest.typeId) })
                        }
                    }

                    if (actionNodes.isNotEmpty()) {
                        Text(
                            text = "Actions & Delivery",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        actionNodes.forEach { manifest ->
                            NodeSelectionCard(manifest = manifest, onClick = { onSelectType(manifest.typeId) })
                        }
                    }

                    if (extractionNodes.isNotEmpty()) {
                        Text(
                            text = "Extraction & Parsing",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        extractionNodes.forEach { manifest ->
                            NodeSelectionCard(manifest = manifest, onClick = { onSelectType(manifest.typeId) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NodeSelectionCard(
    manifest: NodePluginManifest,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = WorkflowUiHelper.getNodeIcon(manifest.typeId),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(
                    text = manifest.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun NodeSelectionCard(
    type: String,
    onClick: () -> Unit,
) {
    val manifest = BuiltInPluginRegistry.getBuiltInManifestByTypeId(type)
    if (manifest != null) {
        NodeSelectionCard(manifest = manifest, onClick = onClick)
    } else {
        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = WorkflowUiHelper.getNodeIcon(type),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = WorkflowUiHelper.getNodeTitle(type),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = WorkflowUiHelper.getNodeDescription(type),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
