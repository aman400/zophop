package dev.zophop.ui.workflows

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.core.ui.components.SearchableDropdownSelector

@Composable
fun TargetAppMultiSelectPicker(
    selectedPackagesString: String,
    onPackagesChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val installedApps = remember(context) { AppListHelper.getInstalledApps(context) }
    var selectedPackages by remember(selectedPackagesString) {
        mutableStateOf(
            selectedPackagesString
                .split(",", "\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != ".*" }
                .toSet(),
        )
    }
    var showAppPickerSheet by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Target Apps Selection",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (selectedPackages.isEmpty()) "Intercepting notifications from All Apps (.*)" else "Intercepting from ${selectedPackages.size} selected app(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { showAppPickerSheet = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Select Target Apps (${selectedPackages.size})")
        }

        if (selectedPackages.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                selectedPackages.forEach { pkg ->
                    val appInfo = installedApps.find { it.packageName == pkg }
                    val displayName = appInfo?.appName ?: pkg
                    SuggestionChip(
                        onClick = {
                            val newSet = selectedPackages - pkg
                            selectedPackages = newSet
                            onPackagesChanged(if (newSet.isEmpty()) ".*" else newSet.joinToString(", "))
                        },
                        label = { Text("$displayName ✕") },
                    )
                }
            }
        }
    }

    if (showAppPickerSheet) {
        NoSwipeBottomSheet(onDismissRequest = { showAppPickerSheet = false }) {
            var searchQuery by remember { mutableStateOf("") }
            val filteredApps =
                remember(installedApps, searchQuery) {
                    if (searchQuery.isBlank()) {
                        installedApps.filter { it.packageName != ".*" }
                    } else {
                        installedApps.filter {
                            it.packageName != ".*" &&
                                (
                                    it.appName.contains(searchQuery, ignoreCase = true) ||
                                        it.packageName.contains(searchQuery, ignoreCase = true)
                                )
                        }
                    }
                }

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Target Apps",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = { showAppPickerSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search apps by name or package...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filteredApps.forEach { appInfo ->
                        val isSelected = selectedPackages.contains(appInfo.packageName)
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val newSet =
                                            if (isSelected) {
                                                selectedPackages - appInfo.packageName
                                            } else {
                                                selectedPackages +
                                                    appInfo.packageName
                                            }
                                        selectedPackages = newSet
                                        onPackagesChanged(if (newSet.isEmpty()) ".*" else newSet.joinToString(", "))
                                    }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    val newSet =
                                        if (checked) {
                                            selectedPackages + appInfo.packageName
                                        } else {
                                            selectedPackages -
                                                appInfo.packageName
                                        }
                                    selectedPackages = newSet
                                    onPackagesChanged(if (newSet.isEmpty()) ".*" else newSet.joinToString(", "))
                                },
                            )
                            val bitmap =
                                remember(appInfo.icon) {
                                    try {
                                        appInfo.icon?.toBitmap(width = 48, height = 48)?.asImageBitmap()
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            if (bitmap != null) {
                                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
                            } else {
                                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = appInfo.appName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = appInfo.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { showAppPickerSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
fun SingleAppDropdownSelector(
    selectedPackage: String,
    onPackageSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val installedApps = remember(context) { AppListHelper.getInstalledApps(context).filter { it.packageName != ".*" } }
    val selectedApp = installedApps.find { it.packageName == selectedPackage }
    val displayLabel = selectedApp?.appName ?: selectedPackage.ifBlank { "Select Target App" }

    SearchableDropdownSelector(
        selectedItemText = displayLabel,
        label = "Simulated Target Package",
        searchPlaceholder = "Search installed apps...",
        items = installedApps,
        itemText = { "${it.appName} (${it.packageName})" },
        onItemSelected = { app ->
            if (app != null) {
                onPackageSelected(app.packageName)
            }
        },
        isItemSelected = { app -> app.packageName == selectedPackage },
        showNoneOption = false,
        tooltipText = "Select installed application to simulate notification origin during test runs.",
        modifier = Modifier.fillMaxWidth(),
    )
}
