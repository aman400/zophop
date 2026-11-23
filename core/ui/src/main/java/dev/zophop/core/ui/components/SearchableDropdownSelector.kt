package dev.zophop.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchableDropdownSelector(
    selectedItemText: String,
    label: String,
    searchPlaceholder: String = "Search...",
    items: List<T>,
    itemText: (T) -> String,
    onItemSelected: (T?) -> Unit,
    showNoneOption: Boolean = true,
    noneOptionText: String = "None",
    isItemSelected: ((T) -> Boolean)? = null,
    isMultiSelect: Boolean = false,
    onCreateNewClick: (() -> Unit)? = null,
    createNewText: String = "➕ Create New...",
    tooltipText: String? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val filteredItems =
        remember(items, searchQuery) {
            if (searchQuery.isBlank()) {
                items
            } else {
                items.filter { itemText(it).contains(searchQuery, ignoreCase = true) }
            }
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = {
            keyboardController?.hide()
            focusManager.clearFocus()
            expanded = !expanded
            if (!expanded) {
                searchQuery = ""
            }
        },
        modifier = modifier,
    ) {
        InfoTooltipField(
            value = selectedItemText,
            onValueChange = {},
            readOnly = true,
            label = label,
            tooltipText = tooltipText ?: label,
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            singleLine = true,
            additionalTrailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                keyboardController?.hide()
                focusManager.clearFocus()
                expanded = false
                searchQuery = ""
            },
            properties = PopupProperties(focusable = true),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(searchPlaceholder, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                keyboardController?.show()
                            }
                        },
            )

            if (showNoneOption) {
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isItemSelected != null && !isMultiSelect) {
                                RadioButton(
                                    selected = items.none { isItemSelected(it) },
                                    onClick = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(noneOptionText)
                        }
                    },
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onItemSelected(null)
                        expanded = false
                        searchQuery = ""
                    },
                )
            }

            filteredItems.forEach { item ->
                val selected = isItemSelected?.invoke(item) ?: false
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (isItemSelected != null) {
                                if (isMultiSelect) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                } else {
                                    RadioButton(
                                        selected = selected,
                                        onClick = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Text(itemText(item))
                        }
                    },
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        onItemSelected(item)
                        if (!isMultiSelect) {
                            expanded = false
                            searchQuery = ""
                        }
                    },
                )
            }

            if (onCreateNewClick != null) {
                DropdownMenuItem(
                    text = { Text(createNewText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        expanded = false
                        searchQuery = ""
                        onCreateNewClick()
                    },
                )
            }
        }
    }
}
