package dev.zophop.core.ui.components

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@Composable
fun rememberAlwaysFloatingInteractionSource(): MutableInteractionSource =
    remember {
        object : MutableInteractionSource {
            override val interactions: Flow<Interaction> = flowOf(FocusInteraction.Focus())

            override suspend fun emit(interaction: Interaction) {}

            override fun tryEmit(interaction: Interaction): Boolean = true
        }
    }

@Composable
fun InfoTooltipField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tooltipText: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    placeholder: String? = null,
    supportingText: String? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    additionalTrailingIcon: (@Composable () -> Unit)? = null,
) {
    var showTooltip by remember { mutableStateOf(false) }
    val interactionSource = rememberAlwaysFloatingInteractionSource()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = supportingText?.let { { Text(it, fontSize = 10.sp) } },
        readOnly = readOnly,
        enabled = enabled,
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        interactionSource = interactionSource,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                additionalTrailingIcon?.invoke()
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
        },
    )
}
