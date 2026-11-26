package dev.zophop.ui.workflows

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.ZopHopSpriteBackdrop
import dev.zophop.plugin.api.NodeCategory
import dev.zophop.plugin.api.NodePluginManifest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeScreen(
    workflowTriggerType: String? = null,
    onBack: () -> Unit,
    onSelectNodeType: (String) -> Unit,
) {
    val context = LocalContext.current
    val unifiedRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .UnifiedPluginRegistry(context)
        }
    var regularManifests by remember { mutableStateOf<List<NodePluginManifest>>(emptyList()) }

    LaunchedEffect(workflowTriggerType) {
        val allManifests = unifiedRegistry.getAllManifests()
        regularManifests = allManifests.filter { it.isRegularNode() && it.isSupportedByTrigger(workflowTriggerType) }
    }

    val extractionNodes =
        remember(regularManifests) {
            regularManifests.filter { it.category == NodeCategory.EXTRACTION }
        }
    val conditionCheckNodes =
        remember(regularManifests) {
            regularManifests.filter {
                it.category == NodeCategory.LOGIC &&
                    (it.typeId.contains("CHECK", ignoreCase = true) || it.displayName.contains("Check", ignoreCase = true))
            }
        }
    val logicNodes =
        remember(regularManifests) {
            regularManifests.filter {
                it.category == NodeCategory.LOGIC &&
                    !it.typeId.contains("CHECK", ignoreCase = true) &&
                    !it.displayName.contains("Check", ignoreCase = true)
            }
        }
    val actionNodes =
        remember(regularManifests) {
            regularManifests.filter { it.category == NodeCategory.ACTION }
        }

    ZopHopSpriteBackdrop(seedKey = "add_node_${workflowTriggerType.orEmpty()}") {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { Text("Add Pipeline Step Node") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Column {
                            Text(
                                text = "Condition & Hardware Check Nodes",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Condition check nodes inspect system state (VPN, GPS, Internet, Bluetooth, SIM, Battery), validate rules, and return contextual variables for downstream steps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                if (conditionCheckNodes.isNotEmpty()) {
                    Text(
                        text = "🛡️ System & Condition Checks",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    conditionCheckNodes.forEach { manifest ->
                        PluginNodeSelectionCard(manifest = manifest, onClick = { onSelectNodeType(manifest.typeId) })
                    }
                }

                if (extractionNodes.isNotEmpty()) {
                    Text(
                        text = "🔍 Parsers & Extraction",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    extractionNodes.forEach { manifest ->
                        PluginNodeSelectionCard(manifest = manifest, onClick = { onSelectNodeType(manifest.typeId) })
                    }
                }

                if (logicNodes.isNotEmpty()) {
                    Text(
                        text = "⚙️ Logic & Rules",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    logicNodes.forEach { manifest ->
                        PluginNodeSelectionCard(manifest = manifest, onClick = { onSelectNodeType(manifest.typeId) })
                    }
                }

                if (actionNodes.isNotEmpty()) {
                    Text(
                        text = "🚀 Actions & Delivery",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    actionNodes.forEach { manifest ->
                        PluginNodeSelectionCard(manifest = manifest, onClick = { onSelectNodeType(manifest.typeId) })
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))
            }
        }
    }
}

@Composable
fun PluginNodeSelectionCard(
    manifest: NodePluginManifest,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                Text(text = manifest.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
