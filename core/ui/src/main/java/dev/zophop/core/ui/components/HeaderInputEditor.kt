package dev.zophop.core.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
import dev.zophop.domain.model.Credential

@Composable
fun HeaderInputEditor(
    headersString: String,
    onHeadersChanged: (String) -> Unit,
    availableVariables: List<String> = emptyList(),
    attachedCredential: Credential? = null,
    isLockEnabled: Boolean = false,
) {
    KeyValueInputEditor(
        title = "HTTP Headers",
        keyLabel = "Header",
        valueLabel = "Value",
        addItemLabel = "Add Header",
        pairSeparator = ": ",
        joinSeparator = "\n",
        exampleText = "Examples:\n• Content-Type: application/json\n• Authorization: Bearer <token>\n• Authorization: Bearer ${'$'}{token}\n\nTip: Click the 🔒 icon to mark/unmark any header as a secret.",
        inputString = headersString,
        onInputChanged = onHeadersChanged,
        availableVariables = availableVariables,
        attachedCredential = attachedCredential,
        allowSecretToggle = true,
        isLockEnabled = isLockEnabled,
    )
}

@Composable
fun QueryParamsInputEditor(
    queryParamsString: String,
    onQueryParamsChanged: (String) -> Unit,
    availableVariables: List<String> = emptyList(),
) {
    KeyValueInputEditor(
        title = "Query Parameters",
        keyLabel = "Parameter",
        valueLabel = "Value",
        addItemLabel = "Add Parameter",
        pairSeparator = "=",
        joinSeparator = "&",
        exampleText = "Examples:\n• page=1\n• limit=10\n• filter=${'$'}{status}\n\nTip: Click the 🔒 icon to mark sensitive parameters (e.g. api_key) as a secret.",
        inputString = queryParamsString,
        onInputChanged = onQueryParamsChanged,
        availableVariables = availableVariables,
        allowSecretToggle = true,
    )
}

data class KeyValueItem(
    val key: String,
    val value: String,
    val isSecret: Boolean = false,
)

fun isLikelySecretHeader(key: String): Boolean {
    val lower = key.trim().lowercase()
    return lower == "authorization" ||
        lower == "proxy-authorization" ||
        lower == "x-api-key" ||
        lower == "api-key" ||
        lower == "apikey" ||
        lower == "token" ||
        lower.contains("secret") ||
        lower.contains("token") ||
        lower.contains("auth") ||
        lower.contains("key") ||
        lower.contains("password")
}

fun getCredentialHeaderName(credential: Credential?): String? {
    if (credential == null) return null
    val adapter = BuiltInPluginRegistry.getHttpCredentialAdapter(credential.type)
    if (adapter != null) {
        return adapter.getSecretHeaderName(credential.dataMap)
    }
    return if (!credential.dataMap["token"].isNullOrBlank()) "Authorization" else null
}

private fun parseInputToKeyValueItems(inputString: String): List<KeyValueItem> {
    if (inputString.isBlank()) return listOf(KeyValueItem("", ""))
    val lines =
        if (inputString.contains("\n")) {
            inputString.split("\r\n", "\n")
        } else if (inputString.contains("&")) {
            inputString.split("&")
        } else if (inputString.contains(",") && Regex(",\\s*[a-zA-Z0-9_-]+[:=]").containsMatchIn(inputString)) {
            inputString.split(Regex(",(?=\\s*[a-zA-Z0-9_-]+[:=])"))
        } else {
            listOf(inputString)
        }
    return lines
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                val parts = trimmed.split(Regex("[:=]"), limit = 2)
                val key = parts.getOrNull(0)?.trim().orEmpty()
                val value = parts.getOrNull(1)?.trim().orEmpty()
                KeyValueItem(key, value, isLikelySecretHeader(key))
            }
        }.ifEmpty { listOf(KeyValueItem("", "")) }
}

@Composable
fun KeyValueInputEditor(
    title: String = "Headers",
    keyLabel: String = "Name",
    valueLabel: String = "Value",
    addItemLabel: String = "Add Item",
    pairSeparator: String = ": ",
    joinSeparator: String = "\n",
    exampleText: String = "Example: Content-Type: application/json",
    inputString: String,
    onInputChanged: (String) -> Unit,
    availableVariables: List<String> = emptyList(),
    attachedCredential: Credential? = null,
    allowSecretToggle: Boolean = true,
    isLockEnabled: Boolean = false,
) {
    val context = LocalContext.current
    var items by remember(inputString) {
        mutableStateOf(parseInputToKeyValueItems(inputString))
    }

    var revealedIndices by remember { mutableStateOf(setOf<Int>()) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }

    fun updateString(currentItems: List<KeyValueItem>) {
        val formatted =
            currentItems
                .filter { it.key.isNotBlank() || it.value.isNotBlank() }
                .joinToString(joinSeparator) { "${it.key}$pairSeparator${it.value}" }
        onInputChanged(formatted)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HeaderEditorTopBar(
            title = title,
            addItemLabel = addItemLabel,
            showInfo = showInfo,
            onToggleInfo = { showInfo = !showInfo },
            onAddItem = {
                val updated = items + KeyValueItem("", "")
                items = updated
                activeIndex = updated.lastIndex
                updateString(updated)
            },
        )

        AnimatedVisibility(visible = showInfo) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = exampleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        if (attachedCredential != null) {
            val credHeader = getCredentialHeaderName(attachedCredential)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Attached Credential: ${attachedCredential.name}${if (credHeader != null) " ('$credHeader' header supplied automatically)" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (availableVariables.isNotEmpty()) {
            VariableSuggestionRow(
                availableVariables = availableVariables,
                onInsertVariable = { variable ->
                    val targetIdx = activeIndex.coerceIn(0, items.lastIndex)
                    val currentItem = items[targetIdx]
                    val token = "\${$variable}"
                    val updatedVal =
                        if (currentItem.value.isEmpty()) {
                            token
                        } else {
                            "${currentItem.value}$token"
                        }
                    items = items.toMutableList().also { it[targetIdx] = currentItem.copy(value = updatedVal) }
                    updateString(items)
                },
            )
        }

        items.forEachIndexed { index, item ->
            KeyValueRow(
                item = item,
                keyLabel = keyLabel,
                valueLabel = valueLabel,
                allowSecretToggle = allowSecretToggle,
                isRevealed = revealedIndices.contains(index),
                availableVariables = availableVariables,
                attachedCredential = attachedCredential,
                onKeyChange = { newKey ->
                    activeIndex = index
                    val newIsSecret = item.isSecret || isLikelySecretHeader(newKey)
                    items = items.toMutableList().also { it[index] = item.copy(key = newKey, isSecret = newIsSecret) }
                    updateString(items)
                },
                onValueChange = { newVal ->
                    activeIndex = index
                    items = items.toMutableList().also { it[index] = item.copy(value = newVal) }
                    updateString(items)
                },
                onToggleSecret = {
                    val toggled = !item.isSecret
                    items = items.toMutableList().also { it[index] = item.copy(isSecret = toggled) }
                    if (!toggled) {
                        revealedIndices = revealedIndices - index
                    }
                },
                onToggleReveal = {
                    if (revealedIndices.contains(index)) {
                        revealedIndices = revealedIndices - index
                    } else if (isLockEnabled) {
                        BiometricAuthHelper.requestBiometricUnlock(
                            context = context,
                            title = "Unlock Vault Credential",
                            subtitle = "Authenticate using biometrics or device PIN/pattern to view secret value",
                            onSuccess = { revealedIndices = revealedIndices + index },
                            onError = { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            },
                        )
                    } else {
                        revealedIndices = revealedIndices + index
                    }
                },
                onRemove = {
                    val updated = items.toMutableList().apply { removeAt(index) }
                    items = updated.ifEmpty { listOf(KeyValueItem("", "")) }
                    activeIndex = activeIndex.coerceAtMost(items.lastIndex)
                    revealedIndices = revealedIndices.filter { it != index }.map { if (it > index) it - 1 else it }.toSet()
                    updateString(items)
                },
            )
        }
    }
}

@Composable
private fun HeaderEditorTopBar(
    title: String,
    addItemLabel: String,
    showInfo: Boolean,
    onToggleInfo: () -> Unit,
    onAddItem: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(
                onClick = onToggleInfo,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Examples",
                    tint = if (showInfo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        TextButton(onClick = onAddItem) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(addItemLabel, fontSize = 12.sp)
        }
    }
}

@Composable
private fun VariableSuggestionRow(
    availableVariables: List<String>,
    onInsertVariable: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Insert variable:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        availableVariables.forEach { variable ->
            SuggestionChip(
                onClick = { onInsertVariable(variable) },
                label = {
                    Text(
                        text = "\${$variable}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                },
            )
        }
    }
}

@Composable
private fun KeyValueRow(
    item: KeyValueItem,
    keyLabel: String,
    valueLabel: String,
    allowSecretToggle: Boolean,
    isRevealed: Boolean,
    availableVariables: List<String>,
    attachedCredential: Credential?,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onToggleSecret: () -> Unit,
    onToggleReveal: () -> Unit,
    onRemove: () -> Unit,
) {
    val credHeaderName = getCredentialHeaderName(attachedCredential)
    val isCredHeader = credHeaderName != null && item.key.equals(credHeaderName, ignoreCase = true)
    val effectiveIsSecret = item.isSecret || isCredHeader
    val isUsingCredentialDefault = isCredHeader && item.value.isBlank() && attachedCredential != null

    val valueState = rememberTextFieldState(item.value)
    val currentItemValue by rememberUpdatedState(item.value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    var lastEmittedValue by remember { mutableStateOf(item.value) }

    LaunchedEffect(item.value) {
        if (item.value != lastEmittedValue && valueState.text.toString() != item.value) {
            lastEmittedValue = item.value
            valueState.setTextAndPlaceCursorAtEnd(item.value)
        }
    }

    LaunchedEffect(valueState) {
        snapshotFlow { valueState.text.toString() }
            .collect { updated ->
                lastEmittedValue = updated
                if (updated != currentItemValue) {
                    currentOnValueChange(updated)
                }
            }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = item.key,
                onValueChange = onKeyChange,
                label = { Text(keyLabel) },
                modifier = Modifier.weight(0.9f),
                singleLine = true,
            )
            OutlinedTextField(
                state = valueState,
                label = { Text(valueLabel) },
                placeholder =
                    if (isUsingCredentialDefault) {
                        { Text("•••••••• (via ${attachedCredential.name})") }
                    } else {
                        null
                    },
                modifier = Modifier.weight(1.1f),
                lineLimits = TextFieldLineLimits.SingleLine,
                outputTransformation =
                    if (effectiveIsSecret && !isRevealed && !isUsingCredentialDefault) {
                        OutputTransformation {
                            for (i in 0 until length) {
                                replace(i, i + 1, "\u2022")
                            }
                        }
                    } else {
                        null
                    },
                trailingIcon = {
                    KeyValueTrailingIcons(
                        isSecret = effectiveIsSecret,
                        isRevealed = isRevealed,
                        hasValue = valueState.text.isNotBlank(),
                        availableVariables = availableVariables,
                        onToggleReveal = onToggleReveal,
                        onInsertVariable = { variable ->
                            val token = "\${$variable}"
                            val currentText = valueState.text.toString()
                            val updatedVal = if (currentText.isEmpty()) token else "$currentText$token"
                            valueState.setTextAndPlaceCursorAtEnd(updatedVal)
                            currentOnValueChange(updatedVal)
                        },
                    )
                },
            )
            if (allowSecretToggle) {
                IconButton(
                    onClick = onToggleSecret,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (effectiveIsSecret) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (effectiveIsSecret) "Secret field - click to unmark" else "Mark as secret field",
                        tint = if (effectiveIsSecret) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove Item",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (isCredHeader && attachedCredential != null) {
            val helperMsg =
                if (item.value.isBlank()) {
                    "🔒 Secret will be used from credential: ${attachedCredential.name}. Enter value above only to override."
                } else {
                    "✏️ Custom header override active (leave blank to use credential default)."
                }
            Text(
                text = helperMsg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun KeyValueTrailingIcons(
    isSecret: Boolean,
    isRevealed: Boolean,
    hasValue: Boolean,
    availableVariables: List<String>,
    onToggleReveal: () -> Unit,
    onInsertVariable: (String) -> Unit,
) {
    var showVarMenu by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (isSecret && hasValue) {
            IconButton(
                onClick = onToggleReveal,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (isRevealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (isRevealed) "Hide secret value" else "View secret value",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (availableVariables.isNotEmpty()) {
            Box {
                IconButton(
                    onClick = { showVarMenu = true },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        text = "{ }",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(
                    expanded = showVarMenu,
                    onDismissRequest = { showVarMenu = false },
                ) {
                    availableVariables.forEach { variable ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "\${$variable}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                )
                            },
                            onClick = {
                                onInsertVariable(variable)
                                showVarMenu = false
                            },
                        )
                    }
                }
            }
        }
    }
}
