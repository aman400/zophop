package dev.zophop.core.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SearchableDropdownSelector(
    selectedItemText: String,
    label: String,
    modifier: Modifier = Modifier,
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
) {
    var showBottomSheet by remember { mutableStateOf(value = false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredItems =
        remember(items, searchQuery) {
            if (searchQuery.isBlank()) {
                items
            } else {
                items.filter { itemText(it).contains(searchQuery.trim(), ignoreCase = true) }
            }
        }

    Box(
        modifier =
            modifier.clickable {
                searchQuery = ""
                showBottomSheet = true
            },
    ) {
        OutlinedTextField(
            value = selectedItemText,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Select $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors =
                OutlinedTextFieldDefaults.colors(
                    disabledTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }

    if (showBottomSheet) {
        BackHandler(enabled = true) {
            showBottomSheet = false
            searchQuery = ""
        }

        val sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
                confirmValueChange = { it != SheetValue.Hidden },
            )

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
                // Title Bar with Explicit Close Button
                DropdownBottomSheetTitleBar(
                    title = label,
                    tooltipText = tooltipText,
                    totalCount = items.size,
                    onCloseClick = {
                        showBottomSheet = false
                        searchQuery = ""
                    },
                )

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(searchPlaceholder) },
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
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                )

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 0.5.dp,
                )

                // Virtualized List
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 420.dp)
                            .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (showNoneOption) {
                        item(key = "__none_option__") {
                            val isNoneSelected = items.none { isItemSelected?.invoke(it) == true }
                            DropdownItemRow(
                                text = noneOptionText,
                                isSelected = isNoneSelected,
                                isMultiSelect = isMultiSelect,
                                onClick = {
                                    onItemSelected(null)
                                    if (!isMultiSelect) {
                                        showBottomSheet = false
                                        searchQuery = ""
                                    }
                                },
                            )
                        }
                    }

                    if (filteredItems.isEmpty()) {
                        item(key = "__empty_state__") {
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
                                        text = "No options matching \"$searchQuery\"",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredItems) { item ->
                            val selected = isItemSelected?.invoke(item) ?: false
                            DropdownItemRow(
                                text = itemText(item),
                                isSelected = selected,
                                isMultiSelect = isMultiSelect,
                                onClick = {
                                    onItemSelected(item)
                                    if (!isMultiSelect) {
                                        showBottomSheet = false
                                        searchQuery = ""
                                    }
                                },
                            )
                        }
                    }
                }

                // Action buttons at the bottom of the bottom sheet
                DropdownBottomSheetBottomBar(
                    createNewText = createNewText,
                    onCreateNewClick =
                        onCreateNewClick?.let { onClick ->
                            {
                                showBottomSheet = false
                                searchQuery = ""
                                onClick()
                            }
                        },
                    isMultiSelect = isMultiSelect,
                    onDoneClick =
                        if (isMultiSelect) {
                            {
                                showBottomSheet = false
                                searchQuery = ""
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
private fun DropdownBottomSheetTitleBar(
    title: String,
    tooltipText: String?,
    totalCount: Int,
    onCloseClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (totalCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = "$totalCount",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (!tooltipText.isNullOrBlank() && !tooltipText.equals(title, ignoreCase = true)) {
                Text(
                    text = tooltipText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        IconButton(
            onClick = onCloseClick,
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
}

@Composable
private fun DropdownBottomSheetBottomBar(
    createNewText: String,
    onCreateNewClick: (() -> Unit)?,
    isMultiSelect: Boolean,
    onDoneClick: (() -> Unit)?,
) {
    if (onCreateNewClick == null && (!isMultiSelect || onDoneClick == null)) return

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 0.5.dp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCreateNewClick != null) {
                FilledTonalButton(
                    onClick = onCreateNewClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier =
                        if (isMultiSelect && onDoneClick != null) {
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = createNewText.removePrefix("➕ ").trim(),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (isMultiSelect && onDoneClick != null) {
                Button(
                    onClick = onDoneClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier =
                        if (onCreateNewClick != null) {
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        },
                ) {
                    Text(
                        text = "Done",
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownItemRow(
    text: String,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
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
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (isMultiSelect) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.size(20.dp),
                    colors =
                        CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            } else {
                RadioButton(
                    selected = isSelected,
                    onClick = null,
                    modifier = Modifier.size(20.dp),
                    colors =
                        RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f),
            )

            if (isSelected && !isMultiSelect) {
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
