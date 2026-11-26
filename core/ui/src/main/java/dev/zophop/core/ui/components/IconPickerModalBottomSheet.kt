package dev.zophop.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class IconCategory(
    val name: String,
    val icon: String,
    val items: List<String>,
)

private val DEFAULT_ICON_CATEGORIES: List<IconCategory> =
    listOf(
        IconCategory(
            name = "Automation",
            icon = "⚡",
            items =
                listOf(
                    "⚡", "🚀", "🤖", "⚙️", "🔁", "🔀", "🎯", "🛠️", "💡", "🔮",
                    "🪄", "⏱️", "⏳", "⏰", "🗓️", "🔥", "✨", "🧩", "📦", "🏷️",
                ),
        ),
        IconCategory(
            name = "Triggers",
            icon = "💬",
            items =
                listOf(
                    "💬", "📩", "📨", "✉️", "🔔", "📢", "📱", "☎️", "📞", "📟",
                    "🗣️", "📬", "🔊", "📻", "📡", "🎙️", "📝", "📋", "📄", "📑",
                ),
        ),
        IconCategory(
            name = "Network",
            icon = "🌐",
            items =
                listOf(
                    "🌐", "🔗", "📶", "🛰️", "☁️", "🌍", "💻", "🖥️", "🔌", "📥",
                    "📤", "🛡️", "🛜", "📡", "⚓", "⚡", "🧭", "🏷️", "📡", "💾",
                ),
        ),
        IconCategory(
            name = "Security",
            icon = "🛡️",
            items =
                listOf(
                    "🛡️", "🔒", "🔐", "🔑", "🚪", "🛑", "🚫", "👮", "🚨", "👁️",
                    "⚠️", "🔍", "🩺", "🪪", "🚦", "🧯", "🧱", "🕶️", "💼", "💳",
                ),
        ),
        IconCategory(
            name = "Hardware",
            icon = "📍",
            items =
                listOf(
                    "📍", "🧭", "🔋", "🔌", "📳", "🔊", "🔇", "📷", "🎥", "💡",
                    "🌡️", "☀️", "🌙", "✈️", "🚗", "🏃", "🚶", "🏠", "🏢", "🎧",
                ),
        ),
        IconCategory(
            name = "Status",
            icon = "✅",
            items =
                listOf(
                    "✅", "❌", "🟢", "🟡", "🔴", "🔵", "🟣", "⚪", "⭐", "🌟",
                    "💎", "🎯", "🏁", "☕", "🏆", "🎖️", "📌", "🔖", "❤️", "👍",
                ),
        ),
    )

/**
 * Clickable badge to display or select an icon/emoji.
 */
@Composable
fun IconSelectorBadge(
    selectedIcon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Icon",
    contentDescription: String = "Select icon",
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color =
            if (selectedIcon.isNotBlank()) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        border =
            BorderStroke(
                width = 1.dp,
                color =
                    if (selectedIcon.isNotBlank()) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
            ),
        modifier = modifier.size(width = 56.dp, height = 56.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (selectedIcon.isNotBlank()) {
                Text(
                    text = selectedIcon,
                    fontSize = 24.sp,
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Material 3 Modal Bottom Sheet for selecting an icon/emoji.
 */
@Composable
fun IconPickerModalBottomSheet(
    selectedIcon: String,
    onIconSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose Icon",
    subtitle: String = "Select an icon or enter any emoji",
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryIndex by remember { mutableStateOf(0) }

    val allIcons = remember { DEFAULT_ICON_CATEGORIES.flatMap { it.items }.distinct() }

    val displayedIcons =
        remember(searchQuery, selectedCategoryIndex) {
            val q = searchQuery.trim()
            if (q.isNotEmpty()) {
                allIcons.filter { it.contains(q, ignoreCase = true) }
            } else if (selectedCategoryIndex == 0) {
                allIcons
            } else {
                DEFAULT_ICON_CATEGORIES.getOrNull(selectedCategoryIndex - 1)?.items ?: allIcons
            }
        }

    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Title, Subtitle, Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close icon picker",
                    )
                }
            }

            HorizontalDivider()

            // Search & Custom Emoji input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search or type any emoji...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // If user typed a custom emoji or character that isn't in default set, offer direct selection
            if (searchQuery.trim().isNotEmpty() && !displayedIcons.contains(searchQuery.trim())) {
                val customInput = searchQuery.trim()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            onIconSelected(customInput)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Use \"$customInput\" as icon")
                    }
                }
            }

            // Category Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    FilterChip(
                        selected = selectedCategoryIndex == 0,
                        onClick = {
                            selectedCategoryIndex = 0
                            searchQuery = ""
                        },
                        label = { Text("All (${allIcons.size})", fontSize = 12.sp) },
                    )
                }
                items(DEFAULT_ICON_CATEGORIES.indices.toList()) { idx ->
                    val cat = DEFAULT_ICON_CATEGORIES[idx]
                    FilterChip(
                        selected = selectedCategoryIndex == idx + 1,
                        onClick = {
                            selectedCategoryIndex = idx + 1
                            searchQuery = ""
                        },
                        label = { Text("${cat.icon} ${cat.name}", fontSize = 12.sp) },
                    )
                }
            }

            // Icons Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(displayedIcons) { iconEmoji ->
                    val isSelected = selectedIcon == iconEmoji
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                    },
                                )
                                .clickable {
                                    onIconSelected(iconEmoji)
                                    onDismiss()
                                },
                    ) {
                        Text(
                            text = iconEmoji,
                            fontSize = 22.sp,
                        )
                    }
                }
            }

            HorizontalDivider()

            // Bottom Actions: Clear / Done
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedIcon.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onIconSelected("")
                            onDismiss()
                        },
                    ) {
                        Text("Remove Icon", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Button(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}
