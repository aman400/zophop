package dev.zophop.ui.workflows

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.zophop.core.ui.components.HeaderInputEditor
import dev.zophop.core.ui.components.InfoTooltipField
import dev.zophop.core.ui.components.QueryParamsInputEditor
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.NodePluginManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun DynamicNodeConfigEditor(
    manifest: NodePluginManifest,
    configJson: String,
    onConfigChanged: (String) -> Unit,
    availableVariables: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val currentConfig =
        remember(configJson, manifest.typeId) {
            mergeConfigJson(configJson, manifest.typeId)
        }

    fun updateConfigKey(
        key: String,
        value: Any,
    ) {
        val newMap = currentConfig.toMutableMap()
        newMap[key] = JsonPrimitive(value.toString())
        onConfigChanged(JsonObject(newMap).toString())
    }

    Column(modifier = modifier.fillMaxWidth()) {
        manifest.configSchema.forEach { fieldSpec ->
            RenderDynamicField(
                fieldSpec = fieldSpec,
                currentConfig = currentConfig,
                updateConfigKey = ::updateConfigKey,
                availableVariables = availableVariables,
            )
        }
    }
}

private fun mergeConfigJson(configJson: String, typeId: String): JsonObject {
    val defaultConfig = parseJsonObjectSafe(WorkflowUiHelper.getDefaultConfigForType(typeId))
    val userConfig = parseJsonObjectSafe(configJson)
    val merged = defaultConfig.toMutableMap()
    for ((k, v) in userConfig) {
        if (v.toString().isNotBlank()) {
            merged[k] = v
        }
    }
    return JsonObject(merged)
}

@Composable
private fun RenderDynamicField(
    fieldSpec: ConfigFieldSpec,
    currentConfig: JsonObject,
    updateConfigKey: (String, Any) -> Unit,
    availableVariables: List<String>,
) {
    when (fieldSpec) {
        is ConfigFieldSpec.TextField -> {
            val value = currentConfig.optString(fieldSpec.key, "")
            val isSecret =
                fieldSpec.isSecret ||
                    fieldSpec.key.equals("token", ignoreCase = true) ||
                    fieldSpec.key.equals("appToken", ignoreCase = true) ||
                    fieldSpec.key.equals("botToken", ignoreCase = true) ||
                    fieldSpec.key.equals("password", ignoreCase = true)
            AutocompleteTextField(
                value = value,
                onValueChange = { updateConfigKey(fieldSpec.key, it) },
                label = fieldSpec.label,
                tooltipText = fieldSpec.tooltip ?: fieldSpec.label,
                placeholder = fieldSpec.placeholder,
                modifier = Modifier.fillMaxWidth(),
                singleLine = fieldSpec.singleLine,
                minLines = fieldSpec.minLines,
                availableVariables = availableVariables,
                insertRawVariable = false,
                isSecret = isSecret,
            )
        }
        is ConfigFieldSpec.DropdownField -> {
            DynamicDropdownField(
                fieldSpec = fieldSpec,
                currentConfig = currentConfig,
                updateConfigKey = updateConfigKey,
            )
        }
        is ConfigFieldSpec.CodeTemplateField -> {
            val value = currentConfig.optString(fieldSpec.key, "")
            if (fieldSpec.key.contains("headers", ignoreCase = true)) {
                HeaderInputEditor(
                    headersString = value,
                    onHeadersChanged = { updateConfigKey(fieldSpec.key, it) },
                    availableVariables = availableVariables,
                )
            } else if (fieldSpec.key.contains("queryParam", ignoreCase = true) || fieldSpec.key.equals("params", ignoreCase = true)) {
                QueryParamsInputEditor(
                    queryParamsString = value,
                    onQueryParamsChanged = { updateConfigKey(fieldSpec.key, it) },
                    availableVariables = availableVariables,
                )
            } else {
                AutocompleteTextField(
                    value = value,
                    onValueChange = { updateConfigKey(fieldSpec.key, it) },
                    label = fieldSpec.label,
                    tooltipText = fieldSpec.tooltip ?: fieldSpec.label,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = fieldSpec.minLines,
                    availableVariables = availableVariables,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicDropdownField(
    fieldSpec: ConfigFieldSpec.DropdownField,
    currentConfig: JsonObject,
    updateConfigKey: (String, Any) -> Unit,
) {
    val defaultOption = fieldSpec.options.firstOrNull()?.first ?: ""
    val rawVal = currentConfig.optString(fieldSpec.key, "")
    val selectedVal = if (rawVal.isNotBlank()) rawVal else defaultOption
    val selectedLabel = fieldSpec.options.find { it.first == selectedVal }?.second ?: selectedVal
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(fieldSpec.key, rawVal) {
        if (rawVal.isBlank() && defaultOption.isNotBlank()) {
            updateConfigKey(fieldSpec.key, defaultOption)
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        InfoTooltipField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = fieldSpec.label,
            tooltipText = fieldSpec.tooltip ?: fieldSpec.label,
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            singleLine = true,
            additionalTrailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            fieldSpec.options.forEach { (optionVal, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        updateConfigKey(fieldSpec.key, optionVal)
                        expanded = false
                    },
                )
            }
        }
    }
}
