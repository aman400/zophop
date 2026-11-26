package dev.zophop.ui.workflows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_DIRECT_QUICK_CHIPS = 5
private const val QUICK_CHIPS_PREVIEW_COUNT = 4

/**
 * Inserts or sets a variable at the current cursor/selection position.
 * Preserves all surrounding text and correctly handles incomplete placeholders like "$" or "${var".
 */
internal fun insertVariableAtCursor(
    currentText: String,
    selectionStart: Int,
    selectionEnd: Int,
    variable: String,
    insertRaw: Boolean,
    maxLength: Int? = null,
): Pair<String, Int> =
    if (insertRaw) {
        insertRawVariableAtCursor(currentText, selectionStart, selectionEnd, variable, maxLength)
    } else {
        insertTemplateVariableAtCursor(currentText, selectionStart, selectionEnd, variable, maxLength)
    }

private fun insertRawVariableAtCursor(
    currentText: String,
    selectionStart: Int,
    selectionEnd: Int,
    variable: String,
    maxLength: Int?,
): Pair<String, Int> {
    val trimmed = currentText.trim()
    val isSingleIdentifier = trimmed.matches(Regex("^[a-zA-Z0-9_]+$"))
    val (candidate, newCursor) =
        if (currentText.isBlank() || isSingleIdentifier) {
            val res = if (maxLength != null && variable.length > maxLength) variable.take(maxLength) else variable
            res to res.length
        } else {
            val start = selectionStart.coerceIn(0, currentText.length)
            val end = selectionEnd.coerceIn(start, currentText.length)
            val prefix = currentText.substring(0, start)
            val suffix = currentText.substring(end)
            val rawCandidate = prefix + variable + suffix
            val res = if (maxLength != null && rawCandidate.length > maxLength) rawCandidate.take(maxLength) else rawCandidate
            val cursor = (start + variable.length).coerceAtMost(res.length)
            res to cursor
        }
    return candidate to newCursor
}

private fun insertTemplateVariableAtCursor(
    currentText: String,
    selectionStart: Int,
    selectionEnd: Int,
    variable: String,
    maxLength: Int?,
): Pair<String, Int> {
    val token = "\${$variable}"
    val start = selectionStart.coerceIn(0, currentText.length)
    val end = selectionEnd.coerceIn(start, currentText.length)

    val textBeforeCursor = currentText.substring(0, start)
    val lastDollar = textBeforeCursor.lastIndexOf('$')
    val isCompletingPlaceholder =
        if (lastDollar >= 0) {
            val snippet = textBeforeCursor.substring(lastDollar)
            snippet == "$" || (snippet.startsWith("\${") && !snippet.contains('}') && !snippet.contains(' ') && !snippet.contains('\n'))
        } else {
            false
        }

    val (prefix, suffix) =
        if (isCompletingPlaceholder) {
            val p = currentText.substring(0, lastDollar)
            val after = currentText.substring(end)
            val s = if (after.startsWith("}")) after.substring(1) else after
            p to s
        } else {
            currentText.substring(0, start) to currentText.substring(end)
        }

    val rawCandidate = prefix + token + suffix
    val candidate = if (maxLength != null && rawCandidate.length > maxLength) rawCandidate.take(maxLength) else rawCandidate
    val newCursor = (prefix.length + token.length).coerceAtMost(candidate.length)
    return candidate to newCursor
}

@Composable
private fun rememberSyncedTextFieldState(
    value: String,
    maxLength: Int?,
    onValueChange: (String) -> Unit,
): TextFieldState {
    val textFieldState = rememberTextFieldState(value)
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var lastEmittedValue by remember { mutableStateOf(value) }

    LaunchedEffect(value) {
        if (value != lastEmittedValue && textFieldState.text.toString() != value) {
            lastEmittedValue = value
            textFieldState.setTextAndPlaceCursorAtEnd(value)
        }
    }

    LaunchedEffect(textFieldState, maxLength) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { updated ->
                val constrained =
                    if (maxLength != null && updated.length > maxLength) {
                        updated.take(maxLength)
                    } else {
                        updated
                    }
                if (constrained != updated) {
                    textFieldState.setTextAndPlaceCursorAtEnd(constrained)
                }
                lastEmittedValue = constrained
                if (constrained != currentValue) {
                    currentOnValueChange(constrained)
                }
            }
    }

    return textFieldState
}

@Composable
fun AutocompleteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    tooltipText: String? = null,
    placeholder: String? = null,
    minLines: Int = 1,
    singleLine: Boolean = false,
    maxLength: Int? = null,
    availableVariables: List<String> = emptyList(),
    insertRawVariable: Boolean = false,
    isSecret: Boolean = false,
) {
    var showSearchSheet by remember { mutableStateOf(value = false) }
    var passwordVisible by remember { mutableStateOf(!isSecret) }

    val textFieldState = rememberSyncedTextFieldState(value = value, maxLength = maxLength, onValueChange = onValueChange)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    val onInsertVar: (String) -> Unit = { v ->
        val selection = textFieldState.selection
        val (newText, newCursor) =
            insertVariableAtCursor(
                currentText = textFieldState.text.toString(),
                selectionStart = selection.start,
                selectionEnd = selection.end,
                variable = v,
                insertRaw = insertRawVariable,
                maxLength = maxLength,
            )
        textFieldState.edit {
            replace(0, length, newText)
            this.selection = TextRange(newCursor)
        }
        currentOnValueChange(newText)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AutocompleteFieldHeader(
            label = label,
            tooltipText = tooltipText,
            hasVariables = availableVariables.isNotEmpty(),
            isRawVariable = insertRawVariable,
        )

        OutlinedTextField(
            state = textFieldState,
            placeholder =
                if (!placeholder.isNullOrBlank()) {
                    {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                } else {
                    null
                },
            modifier = Modifier.fillMaxWidth(),
            lineLimits =
                if (singleLine) {
                    TextFieldLineLimits.SingleLine
                } else {
                    TextFieldLineLimits.MultiLine(minHeightInLines = minLines)
                },
            inputTransformation = maxLength?.let { InputTransformation.maxLengthTrim(it) },
            outputTransformation =
                if (isSecret && !passwordVisible) {
                    OutputTransformation {
                        for (i in 0 until length) {
                            replace(i, i + 1, "\u2022")
                        }
                    }
                } else {
                    null
                },
            keyboardOptions =
                if (isSecret) {
                    KeyboardOptions(keyboardType = KeyboardType.Password)
                } else {
                    KeyboardOptions.Default
                },
            textStyle =
                if (!insertRawVariable && availableVariables.isNotEmpty()) {
                    TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
            shape = RoundedCornerShape(10.dp),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            trailingIcon =
                if (isSecret || textFieldState.text.isNotEmpty()) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSecret) {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector =
                                            if (passwordVisible) {
                                                Icons.Default.VisibilityOff
                                            } else {
                                                Icons.Default.Visibility
                                            },
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (textFieldState.text.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        textFieldState.clearText()
                                        currentOnValueChange("")
                                    },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear input",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    null
                },
        )

        if (availableVariables.isNotEmpty() || maxLength != null) {
            VariableQuickInsertRow(
                availableVariables = availableVariables,
                insertRawVariable = insertRawVariable,
                currentText = textFieldState.text.toString(),
                maxLength = maxLength,
                onVariableSelected = onInsertVar,
                onOpenSearch = { showSearchSheet = true },
            )
        }
    }

    if (showSearchSheet && availableVariables.isNotEmpty()) {
        VariableSearchBottomSheet(
            variables = availableVariables,
            insertRaw = insertRawVariable,
            currentText = textFieldState.text.toString(),
            onVariableSelected = { selectedVar ->
                onInsertVar(selectedVar)
                showSearchSheet = false
            },
            onDismiss = { showSearchSheet = false },
        )
    }
}

@Composable
private fun AutocompleteFieldHeader(
    label: String,
    tooltipText: String?,
    hasVariables: Boolean,
    isRawVariable: Boolean,
) {
    var showTooltip by remember { mutableStateOf(value = false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (!tooltipText.isNullOrBlank() && !tooltipText.equals(label, ignoreCase = true)) {
                Box {
                    IconButton(
                        onClick = { showTooltip = !showTooltip },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info for $label",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp),
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

        if (hasVariables) {
            Text(
                text = if (isRawVariable) "{ } Variable" else "{ } Template",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun VariableQuickInsertRow(
    availableVariables: List<String>,
    insertRawVariable: Boolean,
    currentText: String,
    maxLength: Int?,
    onVariableSelected: (String) -> Unit,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (availableVariables.isNotEmpty()) {
            val isLargeList = availableVariables.size > MAX_DIRECT_QUICK_CHIPS
            val displayedVars =
                if (isLargeList) {
                    availableVariables.take(QUICK_CHIPS_PREVIEW_COUNT)
                } else {
                    availableVariables
                }

            Row(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (insertRawVariable) "Select:" else "Insert:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )

                displayedVars.forEach { v ->
                    val isSelected = insertRawVariable && currentText.trim() == v
                    SuggestionChip(
                        onClick = { onVariableSelected(v) },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Add,
                                contentDescription = null,
                                tint =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                modifier = Modifier.size(13.dp),
                            )
                        },
                        label = {
                            Text(
                                text = if (insertRawVariable) v else "\${$v}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        colors =
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                labelColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                            ),
                        border = null,
                        shape = RoundedCornerShape(8.dp),
                    )
                }

                if (isLargeList) {
                    AssistChip(
                        onClick = onOpenSearch,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search all variables",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp),
                            )
                        },
                        label = {
                            Text(
                                text = "All (${availableVariables.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        colors =
                            AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        border = null,
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (maxLength != null) {
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Text(
                text = "${currentText.length} / $maxLength",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color =
                    if (currentText.length >= maxLength) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariableSearchBottomSheet(
    variables: List<String>,
    insertRaw: Boolean,
    currentText: String,
    onVariableSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredVars =
        remember(searchQuery, variables) {
            if (searchQuery.isBlank()) {
                variables
            } else {
                variables.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
            }
        }

    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            confirmValueChange = { it != SheetValue.Hidden },
        )

    BackHandler(enabled = true) {
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (insertRaw) "Select Variable" else "Insert Variable",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "${variables.size} available",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                placeholder = { Text("Filter variables...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingIcon =
                    if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )

            if (filteredVars.isEmpty()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = "No variables matching \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 420.dp)
                            .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredVars, key = { it }) { v ->
                        val isSelected = insertRaw && currentText.trim() == v
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onVariableSelected(v) },
                            shape = RoundedCornerShape(12.dp),
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            tonalElevation = if (isSelected) 2.dp else 0.dp,
                        ) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = null,
                                        tint =
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        text = if (insertRaw) v else "\${$v}",
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color =
                                            if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                    )
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
