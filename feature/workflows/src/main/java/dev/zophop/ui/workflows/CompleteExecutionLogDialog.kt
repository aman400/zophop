package dev.zophop.ui.workflows

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.model.AppJson
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PrettyLogJson =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
    }

private val SECTION_HEADER_REGEX = Regex("^---\\s*(.+?)\\s*---$")
private val KEY_VALUE_LINE_REGEX = Regex("^([A-Za-z0-9_ .\\-/]{1,40}):\\s+(.*)$")

internal data class LogSection(
    val title: String,
    val content: String,
    val isSummary: Boolean = false,
)

internal data class StepSummaryStats(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val active: Int,
    val totalDurationMs: Long?,
) {
    fun formatCompactSummary(): String =
        buildList {
            add("$succeeded/$total passed")
            if (failed > 0) add("$failed failed")
            if (skipped > 0) add("$skipped skipped")
            if (active > 0) add("$active active")
        }.joinToString(" • ")
}

internal fun computeStepSummaryStats(steps: List<NodeExecutionStep>): StepSummaryStats {
    var succeeded = 0
    var failed = 0
    var skipped = 0
    var active = 0
    var durationSum = 0L
    var hasDuration = false

    for (step in steps) {
        when (step.state) {
            NodeStepState.SUCCESS -> succeeded++
            NodeStepState.FAILED -> failed++
            NodeStepState.SKIPPED -> skipped++
            NodeStepState.EXECUTING, NodeStepState.PENDING -> active++
        }
        step.durationMs?.let {
            durationSum += it
            hasDuration = true
        }
    }
    return StepSummaryStats(
        total = steps.size,
        succeeded = succeeded,
        failed = failed,
        skipped = skipped,
        active = active,
        totalDurationMs = if (hasDuration && durationSum > 0L) durationSum else null,
    )
}

internal fun parseStructuredLogSections(rawLog: String): List<LogSection> {
    if (rawLog.isBlank()) return emptyList()
    val sections = mutableListOf<LogSection>()
    var currentTitle = "Summary"
    var isSummary = true
    val buffer = mutableListOf<String>()

    fun flush() {
        val text = buffer.joinToString("\n").trim()
        if (text.isNotEmpty()) {
            sections.add(LogSection(title = currentTitle, content = text, isSummary = isSummary))
        }
        buffer.clear()
    }

    for (line in rawLog.lines()) {
        val match = SECTION_HEADER_REGEX.matchEntire(line.trim())
        if (match != null) {
            flush()
            currentTitle = match.groupValues[1].trim()
            isSummary = false
        } else {
            buffer.add(line)
        }
    }
    flush()
    return sections
}

private fun isLikelyJsonText(trimmed: String): Boolean =
    (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))

internal fun prettyPrintIfJson(raw: String): Pair<String, Boolean> {
    val trimmed = raw.trim()
    if (!isLikelyJsonText(trimmed)) {
        return raw to false
    }
    return try {
        val element = AppJson.parseToJsonElement(trimmed)
        PrettyLogJson.encodeToString(element) to true
    } catch (_: Exception) {
        raw to false
    }
}

internal fun buildStepReportText(step: NodeExecutionStep): String =
    buildString {
        appendLine("Node: ${step.nodeName} (${WorkflowUiHelper.getNodeTitle(step.nodeType)})")
        appendLine("State: ${step.state}")
        val timeStr = WorkflowUiHelper.formatExecutionTime(step.executedAt)
        if (timeStr.isNotBlank()) appendLine("Timestamp: $timeStr")
        val durStr = WorkflowUiHelper.formatDuration(step.durationMs)
        if (durStr.isNotBlank()) appendLine("Duration: $durStr")
        if (!step.errorReason.isNullOrBlank()) appendLine("Error: ${step.errorReason}")
        if (step.logMessage.isNotBlank()) {
            appendLine()
            appendLine(step.logMessage.trim())
        }
    }.trim()

internal fun buildWorkflowReportText(
    workflowName: String?,
    workflowId: String,
    executionId: String?,
    status: String?,
    timestamp: Long?,
    errorReason: String?,
    steps: List<NodeExecutionStep>,
): String =
    buildString {
        appendLine("=== Workflow Execution Report ===")
        if (!workflowName.isNullOrBlank()) appendLine("Workflow: $workflowName ($workflowId)") else appendLine("Workflow ID: $workflowId")
        if (!executionId.isNullOrBlank()) appendLine("Execution ID: $executionId")
        if (!status.isNullOrBlank()) appendLine("Status: $status")
        if (timestamp != null) {
            val formatted = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            appendLine("Started: $formatted")
        }
        val stats = computeStepSummaryStats(steps)
        appendLine("Summary: ${stats.formatCompactSummary()}")
        val durStr = WorkflowUiHelper.formatDuration(stats.totalDurationMs)
        if (durStr.isNotBlank()) appendLine("Total Duration: $durStr")
        if (!errorReason.isNullOrBlank()) appendLine("Error Reason: $errorReason")
        appendLine()
        steps.forEachIndexed { index, step ->
            appendLine("--- Step ${index + 1}/${steps.size} ---")
            appendLine(buildStepReportText(step))
            appendLine()
        }
    }.trim()

@Composable
fun CompleteExecutionLogDialog(
    step: NodeExecutionStep,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showRawMode by remember(step) { mutableStateOf(false) }
    val sections = remember(step.logMessage) { parseStructuredLogSections(step.logMessage) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.86f)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CompleteStepLogHeader(step = step, onDismiss = onDismiss)

                StepMetadataChips(step = step)

                val errReason = step.errorReason
                if (!errReason.isNullOrBlank() &&
                    !step.logMessage.trim().startsWith(errReason.trim())
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = errReason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                LogViewControlsRow(
                    showRawMode = showRawMode,
                    onToggleRaw = { showRawMode = it },
                    onCopyAll = {
                        copyLogToClipboard(context, "Step Execution Log", buildStepReportText(step))
                    },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                SelectionContainer(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (showRawMode || sections.isEmpty()) {
                            RawMonospaceLogBox(
                                text = step.logMessage.ifBlank { step.errorReason ?: "No log output recorded for this step." },
                            )
                        } else {
                            sections.forEach { section ->
                                StructuredLogSectionCard(
                                    section = section,
                                    isErrorStep = step.state == NodeStepState.FAILED && section.isSummary,
                                    onCopySection = { title, content ->
                                        copyLogToClipboard(context, title, content)
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@Composable
fun CompleteWorkflowExecutionReportDialog(
    workflowName: String?,
    workflowId: String,
    executionId: String? = null,
    status: String? = null,
    timestamp: Long? = null,
    errorReason: String? = null,
    steps: List<NodeExecutionStep>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val stats = remember(steps) { computeStepSummaryStats(steps) }
    var filterMode by remember { mutableStateOf("ALL") }
    var inspectStep by remember { mutableStateOf<NodeExecutionStep?>(null) }

    val filteredSteps =
        remember(steps, filterMode) {
            when (filterMode) {
                "PASSED" -> steps.filter { it.state == NodeStepState.SUCCESS }
                "ISSUES" -> steps.filter { it.state == NodeStepState.FAILED }
                "SKIPPED" -> steps.filter { it.state == NodeStepState.SKIPPED }
                else -> steps
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Complete Execution Log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = workflowName?.takeIf { it.isNotBlank() } ?: "Workflow $workflowId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = {
                                val report =
                                    buildWorkflowReportText(
                                        workflowName = workflowName,
                                        workflowId = workflowId,
                                        executionId = executionId,
                                        status = status,
                                        timestamp = timestamp,
                                        errorReason = errorReason,
                                        steps = steps,
                                    )
                                copyLogToClipboard(context, "Complete Execution Report", report)
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy All", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                ReportSummaryBanner(
                    status = status,
                    stats = stats,
                    executionId = executionId,
                    errorReason = errorReason,
                )

                if (steps.size > 1) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FilterChip(
                            selected = filterMode == "ALL",
                            onClick = { filterMode = "ALL" },
                            label = { Text("All (${steps.size})", fontSize = 11.sp) },
                        )
                        if (stats.succeeded > 0) {
                            FilterChip(
                                selected = filterMode == "PASSED",
                                onClick = { filterMode = "PASSED" },
                                label = { Text("Passed (${stats.succeeded})", fontSize = 11.sp) },
                            )
                        }
                        if (stats.failed > 0) {
                            FilterChip(
                                selected = filterMode == "ISSUES",
                                onClick = { filterMode = "ISSUES" },
                                label = { Text("Errors (${stats.failed})", fontSize = 11.sp) },
                            )
                        }
                        if (stats.skipped > 0) {
                            FilterChip(
                                selected = filterMode == "SKIPPED",
                                onClick = { filterMode = "SKIPPED" },
                                label = { Text("Skipped (${stats.skipped})", fontSize = 11.sp) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                SelectionContainer(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        filteredSteps.forEachIndexed { idx, step ->
                            ReportStepItemCard(
                                index = idx + 1,
                                step = step,
                                onInspectStep = { inspectStep = step },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }

    inspectStep?.let { step ->
        CompleteExecutionLogDialog(
            step = step,
            onDismiss = { inspectStep = null },
        )
    }
}

@Composable
private fun CompleteStepLogHeader(
    step: NodeExecutionStep,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = WorkflowUiHelper.getNodeIcon(step.nodeType),
            contentDescription = null,
            tint =
                if (step.state == NodeStepState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Complete Execution Log",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${step.nodeName} • ${WorkflowUiHelper.getNodeTitle(step.nodeType)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NodeStepStatusChipCompact(step = step)
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun NodeStepStatusChipCompact(step: NodeExecutionStep) {
    val (label, bg, fg) =
        when (step.state) {
            NodeStepState.SUCCESS ->
                Triple("Success", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            NodeStepState.FAILED ->
                Triple("Failed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
            NodeStepState.SKIPPED ->
                Triple("Skipped", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
            NodeStepState.EXECUTING ->
                Triple("Running", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            NodeStepState.PENDING ->
                Triple("Pending", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun StepMetadataChips(step: NodeExecutionStep) {
    val timeStr = WorkflowUiHelper.formatExecutionTime(step.executedAt)
    val durationStr = WorkflowUiHelper.formatDuration(step.durationMs)
    if (timeStr.isBlank() && durationStr.isBlank() && step.retriedInExecutionId.isNullOrBlank()) return

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (timeStr.isNotBlank()) {
            MetadataBadge(icon = Icons.Default.Schedule, text = timeStr)
        }
        if (durationStr.isNotBlank()) {
            MetadataBadge(icon = Icons.Default.Timer, text = durationStr)
        }
        if (!step.retriedInExecutionId.isNullOrBlank()) {
            MetadataBadge(icon = Icons.Default.CheckCircle, text = "Retried: ${step.retriedInExecutionId}")
        }
    }
}

@Composable
private fun MetadataBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun LogViewControlsRow(
    showRawMode: Boolean,
    onToggleRaw: (Boolean) -> Unit,
    onCopyAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !showRawMode,
                onClick = { onToggleRaw(false) },
                label = { Text("Formatted", fontSize = 11.sp) },
            )
            FilterChip(
                selected = showRawMode,
                onClick = { onToggleRaw(true) },
                label = { Text("Raw Log", fontSize = 11.sp) },
            )
        }

        FilledTonalButton(
            onClick = onCopyAll,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy Log", fontSize = 11.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun StructuredLogSectionCard(
    section: LogSection,
    isErrorStep: Boolean,
    onCopySection: (String, String) -> Unit,
) {
    var expanded by remember(section.title) { mutableStateOf(true) }
    val (prettyText, isJson) = remember(section.content) { prettyPrintIfJson(section.content) }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isErrorStep) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ).clickable { expanded = !expanded }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isErrorStep) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isJson) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = "JSON",
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onCopySection(section.title, prettyText) },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy ${section.title}",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                ) {
                    if (isJson) {
                        RawMonospaceLogBox(text = prettyText)
                    } else {
                        FormattedKeyValueOrText(content = prettyText, isError = isErrorStep)
                    }
                }
            }
        }
    }
}

@Composable
private fun FormattedKeyValueOrText(
    content: String,
    isError: Boolean,
) {
    val lines = remember(content) { content.lines() }
    val kvMatches =
        remember(lines) {
            lines.map { line -> line to KEY_VALUE_LINE_REGEX.matchEntire(line.trim()) }
        }
    val hasMostlyKv = kvMatches.count { it.second != null } >= 2 ||
        (kvMatches.size == 1 && kvMatches.first().second != null)

    if (hasMostlyKv) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            kvMatches.forEach { (rawLine, match) ->
                if (match != null) {
                    val key = match.groupValues[1].trim()
                    val value = match.groupValues[2]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "$key:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(108.dp),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (rawLine.isNotBlank()) {
                    Text(
                        text = rawLine,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    } else {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RawMonospaceLogBox(text: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                ).padding(10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReportSummaryBanner(
    status: String?,
    stats: StepSummaryStats,
    executionId: String?,
    errorReason: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (!status.isNullOrBlank()) {
                    Surface(
                        color =
                            if (status == "SUCCESS") {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            },
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = status,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color =
                                if (status == "SUCCESS") {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                },
                        )
                    }
                }
                Text(
                    text = stats.formatCompactSummary(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val durStr = WorkflowUiHelper.formatDuration(stats.totalDurationMs)
                if (durStr.isNotBlank()) {
                    Text(
                        text = "• $durStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (!executionId.isNullOrBlank()) {
                Text(
                    text = "Execution ID: $executionId",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!errorReason.isNullOrBlank() && status != "SUCCESS") {
                Text(
                    text = errorReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ReportStepItemCard(
    index: Int,
    step: NodeExecutionStep,
    onInspectStep: () -> Unit,
) {
    val sections = remember(step.logMessage) { parseStructuredLogSections(step.logMessage) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = WorkflowUiHelper.getNodeIcon(step.nodeType),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint =
                        if (step.state == NodeStepState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$index. ${step.nodeName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta =
                        listOfNotNull(
                            WorkflowUiHelper.getNodeTitle(step.nodeType),
                            WorkflowUiHelper.formatDuration(step.durationMs).takeIf { it.isNotBlank() },
                        ).joinToString(" • ")
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NodeStepStatusChipCompact(step = step)
                IconButton(onClick = onInspectStep, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Inspect ${step.nodeName} log",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (sections.isNotEmpty()) {
                sections.forEach { section ->
                    val (pretty, _) = prettyPrintIfJson(section.content)
                    Text(
                        text = if (section.isSummary) pretty else "${section.title}:\n$pretty",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color =
                            if (step.state == NodeStepState.FAILED && section.isSummary) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                }
            } else {
                val errReason = step.errorReason
                if (!errReason.isNullOrBlank()) {
                    Text(
                        text = errReason,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private fun copyLogToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
}
