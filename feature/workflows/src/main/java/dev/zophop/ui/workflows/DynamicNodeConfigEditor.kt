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
import dev.zophop.core.ui.components.InfoTooltipField
import dev.zophop.plugin.api.ConfigFieldSpec
import dev.zophop.plugin.api.NodePluginManifest
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
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
            val defaultConfig =
                try {
                    JSONObject(WorkflowUiHelper.getDefaultConfigForType(manifest.typeId))
                } catch (_: Exception) {
                    JSONObject()
                }
            val userConfig =
                try {
                    JSONObject(configJson)
                } catch (_: Exception) {
                    JSONObject()
                }
            val merged = JSONObject()
            val defaultKeys = defaultConfig.keys()
            while (defaultKeys.hasNext()) {
                val k = defaultKeys.next()
                merged.put(k, defaultConfig.opt(k))
            }
            val userKeys = userConfig.keys()
            while (userKeys.hasNext()) {
                val k = userKeys.next()
                val v = userConfig.opt(k)
                if (v != null && v.toString().isNotBlank()) {
                    merged.put(k, v)
                }
            }
            merged
        }

    fun updateConfigKey(
        key: String,
        value: Any,
    ) {
        val newObj =
            try {
                JSONObject(currentConfig.toString())
            } catch (_: Exception) {
                JSONObject()
            }
        newObj.put(key, value)
        onConfigChanged(newObj.toString())
    }

    Column(modifier = modifier.fillMaxWidth()) {
        manifest.configSchema.forEach { fieldSpec ->
            when (fieldSpec) {
                is ConfigFieldSpec.TextField -> {
                    val value = currentConfig.optString(fieldSpec.key, "")
                    InfoTooltipField(
                        value = value,
                        onValueChange = { updateConfigKey(fieldSpec.key, it) },
                        label = fieldSpec.label,
                        tooltipText = fieldSpec.tooltip ?: fieldSpec.label,
                        placeholder = fieldSpec.placeholder,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = fieldSpec.singleLine,
                        minLines = fieldSpec.minLines,
                    )
                }
                is ConfigFieldSpec.DropdownField -> {
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
                is ConfigFieldSpec.CodeTemplateField -> {
                    val value = currentConfig.optString(fieldSpec.key, "")
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
}
