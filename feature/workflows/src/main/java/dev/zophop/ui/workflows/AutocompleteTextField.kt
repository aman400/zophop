package dev.zophop.ui.workflows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.core.ui.components.rememberAlwaysFloatingInteractionSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutocompleteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tooltipText: String? = null,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    singleLine: Boolean = false,
    maxLength: Int? = null,
    availableVariables: List<String> = emptyList(),
    insertRawVariable: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }
    val interactionSource = rememberAlwaysFloatingInteractionSource()

    val filteredVars =
        remember(filterQuery, availableVariables) {
            if (filterQuery.isBlank()) availableVariables else availableVariables.filter { it.contains(filterQuery, ignoreCase = true) }
        }

    Column(modifier = modifier) {
        ExposedDropdownMenuBox(
            expanded = expanded && filteredVars.isNotEmpty(),
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = { newValue ->
                    val constrained = if (maxLength != null && newValue.length > maxLength) newValue.take(maxLength) else newValue
                    onValueChange(constrained)
                    val (shouldExpand, query) = extractFilterQuery(constrained, insertRawVariable)
                    expanded = shouldExpand
                    filterQuery = query
                },
                label = { Text(label) },
                placeholder =
                    if (!placeholder.isNullOrBlank()) {
                        { Text(placeholder) }
                    } else {
                        null
                    },
                interactionSource = interactionSource,
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                minLines = minLines,
                singleLine = singleLine,
                trailingIcon = {
                    AutocompleteTrailingIcons(
                        hasVariables = availableVariables.isNotEmpty(),
                        label = label,
                        tooltipText = tooltipText,
                        onToggleVariables = {
                            filterQuery = ""
                            expanded = !expanded
                        },
                    )
                },
            )

            ExposedDropdownMenu(
                expanded = expanded && filteredVars.isNotEmpty(),
                onDismissRequest = { expanded = false },
            ) {
                filteredVars.forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (insertRawVariable) v else "\${$v}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        onClick = {
                            val newVal = computeVariableInsertion(value, v, insertRawVariable, maxLength)
                            onValueChange(newVal)
                            expanded = false
                            filterQuery = ""
                        },
                    )
                }
            }
        }
        if (maxLength != null) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "${value.length} / $maxLength",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = if (value.length >= maxLength) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun extractFilterQuery(text: String, insertRawVariable: Boolean): Pair<Boolean, String> {
    if (insertRawVariable) return true to text
    val lastDollarIdx = text.lastIndexOf('$')
    return when {
        lastDollarIdx < 0 -> false to ""
        lastDollarIdx == text.length - 1 -> true to ""
        text.length > lastDollarIdx + 1 && text[lastDollarIdx + 1] == '{' -> {
            true to text.substring(lastDollarIdx + 2).trimEnd('}')
        }
        text.substring(lastDollarIdx).startsWith("\${") -> {
            val braceIdx = text.indexOf('}', lastDollarIdx)
            val query =
                if (braceIdx > lastDollarIdx + 2) {
                    text.substring(lastDollarIdx + 2, braceIdx)
                } else {
                    text.substring(lastDollarIdx + 2)
                }
            true to query
        }
        else -> false to ""
    }
}

private fun computeVariableInsertion(
    value: String,
    variable: String,
    insertRaw: Boolean,
    maxLength: Int?,
): String {
    val newVal =
        if (insertRaw) {
            variable
        } else {
            val lastDollarIdx = value.lastIndexOf('$')
            if (lastDollarIdx >= 0 && (lastDollarIdx == value.length - 1 || value.substring(lastDollarIdx).startsWith("\${"))) {
                val prefix = value.substring(0, lastDollarIdx)
                "$prefix\${$variable}"
            } else {
                val spacer = if (value.isNotBlank() && !value.endsWith(" ")) " " else ""
                "$value$spacer\${$variable}"
            }
        }
    return if (maxLength != null && newVal.length > maxLength) newVal.take(maxLength) else newVal
}

@Composable
private fun AutocompleteTrailingIcons(
    hasVariables: Boolean,
    label: String,
    tooltipText: String?,
    onToggleVariables: () -> Unit,
) {
    var showTooltip by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (hasVariables) {
            Box(
                modifier =
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) {
                                    event.changes.forEach { it.consume() }
                                    onToggleVariables()
                                } else if (event.type == PointerEventType.Release) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            ) {
                IconButton(onClick = onToggleVariables) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = "Insert Variable",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (!tooltipText.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) {
                                    event.changes.forEach { it.consume() }
                                    showTooltip = !showTooltip
                                } else if (event.type == PointerEventType.Release) {
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    },
            ) {
                IconButton(onClick = { showTooltip = !showTooltip }) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Field Information",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(
                    expanded = showTooltip,
                    onDismissRequest = { showTooltip = false },
                ) {
                    Column(
                        modifier =
                            Modifier
                                .widthIn(max = 280.dp)
                                .padding(12.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tooltipText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
