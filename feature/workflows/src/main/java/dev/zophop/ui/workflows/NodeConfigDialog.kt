package dev.zophop.ui.workflows

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zophop.core.ui.components.CredentialBottomSheet
import dev.zophop.core.ui.components.HeaderInputEditor
import dev.zophop.core.ui.components.InfoTooltipField
import dev.zophop.core.ui.components.QueryParamsInputEditor
import dev.zophop.core.ui.components.SearchableDropdownSelector
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.toDomain
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.WorkflowNode
import org.json.JSONObject

@Composable
fun NodeConfigBottomSheet(
    node: WorkflowNode,
    availableVariables: List<String> = emptyList(),
    credentials: List<Credential> = emptyList(),
    vaultPreconditions: List<PreconditionEntity> = emptyList(),
    showPreconditionsGuard: Boolean = true,
    hasNotificationListenerPermission: Boolean = true,
    hasPostNotificationsPermission: Boolean = true,
    hasSmsPermission: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (WorkflowNode) -> Unit,
    onSaveCredential: ((Credential) -> Unit)? = null,
    onRequestCreatePrecondition: ((onCreated: (PreconditionEntity) -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val unifiedRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .UnifiedPluginRegistry(context)
        }
    val externalPluginRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .ExternalPluginRegistry(context)
        }
    var pluginInfo by remember { mutableStateOf<dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo?>(null) }
    var pluginManifest by remember { mutableStateOf<dev.zophop.plugin.api.NodePluginManifest?>(null) }

    androidx.compose.runtime.LaunchedEffect(node.type) {
        pluginManifest = unifiedRegistry.getManifestByTypeId(node.type)
        val plugins = externalPluginRegistry.discoverPlugins()
        pluginInfo = plugins.find { it.manifest.typeId == node.type }
    }

    var permissionInstructionData by remember { mutableStateOf<PermissionInstructionData?>(null) }
    var permissionCheckTrigger by remember { mutableIntStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    permissionCheckTrigger++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val multiplePermissionsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { grantMap ->
            permissionCheckTrigger++
            if (!isPermissionGranted(grantMap)) {
                val denied = grantMap.filter { !it.value }.keys.toList()
                permissionInstructionData =
                    WorkflowUiHelper.getPermissionInstruction(
                        context = context,
                        missingPermissions = denied,
                        nodeType = node.type,
                        pluginPackage = pluginInfo?.packageName,
                        setupActivity = pluginManifest?.setupActivity,
                    )
            }
        }

    val requestPermissionAction: () -> Unit = {
        val missing =
            WorkflowUiHelper.getMissingPermissions(
                context = context,
                nodeType = node.type,
                manifest = pluginManifest,
                pluginPackage = pluginInfo?.packageName,
            )
        val runtimePerms = missing.filter { it != "NOTIFICATION_LISTENER" }
        if (runtimePerms.isNotEmpty() && pluginInfo == null) {
            // Try to request using native android permission APIs first
            multiplePermissionsLauncher.launch(runtimePerms.toTypedArray())
        } else {
            // Special permission (e.g. notification listener) or external plugin setup
            permissionInstructionData =
                WorkflowUiHelper.getPermissionInstruction(
                    context = context,
                    missingPermissions = missing,
                    nodeType = node.type,
                    pluginPackage = pluginInfo?.packageName,
                    setupActivity = pluginManifest?.setupActivity,
                )
        }
    }

    val permissionSummary =
        remember(
            permissionCheckTrigger,
            pluginManifest,
            pluginInfo,
            hasNotificationListenerPermission,
            hasPostNotificationsPermission,
            hasSmsPermission,
        ) {
            WorkflowUiHelper.getMissingPermissionSummary(
                context = context,
                nodeType = node.type,
                manifest = pluginManifest,
                pluginPackage = pluginInfo?.packageName,
            )
        }

    val isMissingPerm = permissionSummary != null

    val isConditional =
        remember(node.type, pluginManifest) {
            WorkflowUiHelper.isConditionalNode(node.type, pluginManifest)
        }
    val canShowPreconditionsGuard = showPreconditionsGuard && !isConditional

    var name by remember { mutableStateOf(node.name) }
    var isEnabled by remember { mutableStateOf(node.isEnabled) }

    val initialConfigMap =
        remember(node.configJson, node.type) {
            loadInitialNodeConfig(node.type, node.configJson)
        }
    var configStateMap by remember(initialConfigMap) { mutableStateOf<Map<String, String>>(initialConfigMap) }

    androidx.compose.runtime.LaunchedEffect(pluginManifest, credentials) {
        pluginManifest?.let { manifest ->
            val updated = fillMissingDropdownDefaults(manifest, configStateMap)
            if (updated !== configStateMap) {
                configStateMap = updated
            }
        }
        val currentCredId = configStateMap["credentialId"]?.trim().orEmpty()
        if (currentCredId.isNotBlank()) {
            val matchedCred = credentials.find { it.id == currentCredId }
            if (matchedCred != null) {
                val filled =
                    WorkflowUiHelper.fillMissingCredentialDefaults(
                        nodeType = node.type,
                        pluginManifest = pluginManifest,
                        currentConfig = configStateMap,
                        credential = matchedCred,
                    )
                if (filled != configStateMap) {
                    configStateMap = filled
                }
            }
        }
    }
    var credentialId by remember(configStateMap) { mutableStateOf(configStateMap["credentialId"] ?: "") }

    // Preconditions State - Attached Vault Precondition IDs (disabled for conditional nodes to prevent circular dependencies)
    var attachedVaultPrecIds by remember(canShowPreconditionsGuard) {
        mutableStateOf(
            findInitialAttachedVaultPrecIds(canShowPreconditionsGuard, vaultPreconditions, node.preconditions),
        )
    }

    var showCreateCredSheet by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) {
        onDismiss()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configure Node") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
        bottomBar = {
            NodeConfigBottomBar(
                onDismiss = onDismiss,
                onSaveClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    val (validationError, finalConfigJson) =
                        buildAndValidateNodeConfig(
                            nodeType = node.type,
                            name = name,
                            configStateMap = configStateMap,
                            credentialId = credentialId,
                            pluginManifest = pluginManifest,
                            unifiedRegistry = unifiedRegistry,
                        )
                    if (validationError != null) {
                        errorMessage = validationError
                        return@NodeConfigBottomBar
                    }
                    errorMessage = null

                    val attachedPreconditions =
                        if (canShowPreconditionsGuard) {
                            vaultPreconditions
                                .filter { attachedVaultPrecIds.contains(it.id) && it.isEnabled }
                                .map { it.toDomain() }
                        } else {
                            emptyList()
                        }

                    val updatedNode =
                        node.copy(
                            name = name,
                            isEnabled = !isMissingPerm && isEnabled,
                            configJson = finalConfigJson,
                            preconditions = attachedPreconditions,
                        )
                    onSave(updatedNode)
                },
            )
        },
    ) { padding ->
        // Scrollable Content
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            errorMessage?.let { err ->
                NodeConfigErrorBanner(
                    errorMessage = err,
                    onDismiss = { errorMessage = null },
                )
            }

            InfoTooltipField(
                value = name,
                onValueChange = { name = it },
                label = "Node Name",
                tooltipText = "Unique name identifying this step in the workflow pipeline.",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Permission Warning Banner
            permissionSummary?.let { summary ->
                NodeConfigPermissionBanner(
                    permissionSummary = summary,
                    onRequestPermission = requestPermissionAction,
                )
            }

            // Credential attachment selector for Actions
            if (isCredentialSupported(node.type, pluginManifest)) {
                SubtleSectionDivider()
                NodeConfigCredentialSelector(
                    credentials = credentials,
                    credentialId = credentialId,
                    onCredentialSelected = { selectedCred ->
                        credentialId = selectedCred?.id.orEmpty()
                        configStateMap =
                            WorkflowUiHelper.applyCredentialDefaultsToConfig(
                                nodeType = node.type,
                                pluginManifest = pluginManifest,
                                currentConfig = configStateMap,
                                credential = selectedCred,
                            )
                    },
                    onCreateNewClick = { showCreateCredSheet = true },
                )
            }

            // Dynamic Manifest-Driven Config Fields
            val selectedCred = credentials.find { it.id == credentialId }
            NodeConfigDynamicSchemaSections(
                pluginManifest = pluginManifest,
                configStateMap = configStateMap,
                availableVariables = availableVariables,
                selectedCredential = selectedCred,
                onFieldChange = { key, newValue ->
                    configStateMap = configStateMap + (key to newValue)
                },
            )

            if (canShowPreconditionsGuard) {
                SubtleSectionDivider()
                NodeConfigPreconditionsSection(
                    attachedVaultPrecIds = attachedVaultPrecIds,
                    vaultPreconditions = vaultPreconditions,
                    onAttachedIdsChange = { attachedVaultPrecIds = it },
                    onRequestCreatePrecondition = onRequestCreatePrecondition,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showCreateCredSheet) {
        CredentialBottomSheet(
            credential = null,
            onDismiss = { showCreateCredSheet = false },
            onSave = { newCred ->
                onSaveCredential?.invoke(newCred)
                credentialId = newCred.id
                configStateMap =
                    WorkflowUiHelper.applyCredentialDefaultsToConfig(
                        nodeType = node.type,
                        pluginManifest = pluginManifest,
                        currentConfig = configStateMap,
                        credential = newCred,
                    )
                showCreateCredSheet = false
            },
        )
    }

    permissionInstructionData?.let { data ->
        WorkflowUiHelper.PermissionInstructionDialog(
            data = data,
            onDismiss = { permissionInstructionData = null },
        )
    }
}

@Composable
private fun RenderDynamicConfigField(
    field: dev.zophop.plugin.api.ConfigFieldSpec,
    currentVal: String,
    onValueChange: (String) -> Unit,
    availableVariables: List<String> = emptyList(),
    selectedCredential: Credential? = null,
) {
    when (field.key) {
        "packageFilter" -> {
            TargetAppMultiSelectPicker(
                selectedPackagesString = currentVal,
                onPackagesChanged = onValueChange,
            )
        }
        "testPackageName" -> {
            SingleAppDropdownSelector(
                selectedPackage = currentVal,
                onPackageSelected = onValueChange,
            )
        }
        else -> {
            RenderGenericField(
                field = field,
                currentVal = currentVal,
                onValueChange = onValueChange,
                availableVariables = availableVariables,
                selectedCredential = selectedCredential,
            )
        }
    }
}

@Composable
private fun RenderGenericField(
    field: dev.zophop.plugin.api.ConfigFieldSpec,
    currentVal: String,
    onValueChange: (String) -> Unit,
    availableVariables: List<String>,
    selectedCredential: Credential?,
) {
    when (field) {
        is dev.zophop.plugin.api.ConfigFieldSpec.TextField -> {
            RenderTextFieldConfig(
                field = field,
                currentVal = currentVal,
                onValueChange = onValueChange,
                availableVariables = availableVariables,
                selectedCredential = selectedCredential,
            )
        }
        is dev.zophop.plugin.api.ConfigFieldSpec.DropdownField -> {
            RenderDropdownConfig(
                field = field,
                currentVal = currentVal,
                onValueChange = onValueChange,
            )
        }
        is dev.zophop.plugin.api.ConfigFieldSpec.CodeTemplateField -> {
            RenderCodeTemplateConfig(
                field = field,
                currentVal = currentVal,
                onValueChange = onValueChange,
                availableVariables = availableVariables,
                selectedCredential = selectedCredential,
            )
        }
    }
}

@Composable
private fun RenderTextFieldConfig(
    field: dev.zophop.plugin.api.ConfigFieldSpec.TextField,
    currentVal: String,
    onValueChange: (String) -> Unit,
    availableVariables: List<String>,
    selectedCredential: Credential?,
) {
    val isVariableField =
        (field.key.equals("variable", ignoreCase = true) ||
            field.key.equals("inputVariable", ignoreCase = true) ||
            field.key.endsWith("Variable", ignoreCase = true)) &&
            availableVariables.isNotEmpty()

    val isTokenField =
        field.isSecret ||
            field.key.equals("token", ignoreCase = true) ||
            field.key.equals("appToken", ignoreCase = true) ||
            field.key.equals("botToken", ignoreCase = true) ||
            field.key.equals("password", ignoreCase = true)

    val placeholder = computeEffectivePlaceholder(field, currentVal, selectedCredential, isTokenField)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        AutocompleteTextField(
            value = currentVal,
            onValueChange = onValueChange,
            label = field.label,
            tooltipText = field.tooltip ?: field.label,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
            singleLine = field.singleLine,
            minLines = field.minLines,
            availableVariables = availableVariables,
            insertRawVariable = isVariableField,
            isSecret = isTokenField,
        )

        if (isTokenField && selectedCredential != null) {
            val credToken =
                selectedCredential.dataMap[field.key].takeUnless { it.isNullOrBlank() }
                    ?: selectedCredential.dataMap["token"].takeUnless { it.isNullOrBlank() }
                    ?: selectedCredential.dataMap["appToken"].takeUnless { it.isNullOrBlank() }
                    ?: selectedCredential.dataMap["botToken"].takeUnless { it.isNullOrBlank() }

            if (!credToken.isNullOrBlank()) {
                val helperMsg =
                    if (currentVal.isBlank()) {
                        "🔒 Token will be used from credential: ${selectedCredential.name}. Enter value above only to override."
                    } else {
                        "✏️ Custom token override active (leave blank to use credential default)."
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
}

private fun computeEffectivePlaceholder(
    field: dev.zophop.plugin.api.ConfigFieldSpec.TextField,
    currentVal: String,
    selectedCredential: Credential?,
    isTokenField: Boolean,
): String? {
    if (currentVal.isNotBlank() || selectedCredential == null || !isTokenField) {
        return field.placeholder
    }
    val credToken =
        selectedCredential.dataMap[field.key].takeUnless { it.isNullOrBlank() }
            ?: selectedCredential.dataMap["token"].takeUnless { it.isNullOrBlank() }
            ?: selectedCredential.dataMap["appToken"].takeUnless { it.isNullOrBlank() }
            ?: selectedCredential.dataMap["botToken"].takeUnless { it.isNullOrBlank() }

    return if (!credToken.isNullOrBlank()) {
        if (selectedCredential.name.isNotBlank()) {
            "Token will be used from credential (${selectedCredential.name})"
        } else {
            "Token will be used from credential"
        }
    } else {
        field.placeholder
    }
}

@Composable
private fun RenderDropdownConfig(
    field: dev.zophop.plugin.api.ConfigFieldSpec.DropdownField,
    currentVal: String,
    onValueChange: (String) -> Unit,
) {
    val effectiveVal = currentVal.ifBlank { field.options.firstOrNull()?.first ?: "" }
    androidx.compose.runtime.LaunchedEffect(field.key, currentVal) {
        if (currentVal.isBlank() && effectiveVal.isNotBlank()) {
            onValueChange(effectiveVal)
        }
    }
    val selectedLabel = field.options.find { it.first == effectiveVal }?.second ?: effectiveVal
    SearchableDropdownSelector(
        selectedItemText = selectedLabel,
        label = field.label,
        searchPlaceholder = "Filter ${field.label}...",
        items = field.options,
        itemText = { it.second },
        onItemSelected = { option ->
            if (option != null) {
                onValueChange(option.first)
            }
        },
        isItemSelected = { option -> option.first == effectiveVal },
        showNoneOption = false,
        tooltipText = field.tooltip ?: field.label,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RenderCodeTemplateConfig(
    field: dev.zophop.plugin.api.ConfigFieldSpec.CodeTemplateField,
    currentVal: String,
    onValueChange: (String) -> Unit,
    availableVariables: List<String>,
    selectedCredential: Credential? = null,
) {
    if (field.key.contains("headers", ignoreCase = true)) {
        HeaderInputEditor(
            headersString = currentVal,
            onHeadersChanged = onValueChange,
            availableVariables = availableVariables,
            attachedCredential = selectedCredential,
        )
    } else if (field.key.contains("queryParam", ignoreCase = true) || field.key.equals("params", ignoreCase = true)) {
        QueryParamsInputEditor(
            queryParamsString = currentVal,
            onQueryParamsChanged = onValueChange,
            availableVariables = availableVariables,
        )
    } else if (field.isTestField || field.key.startsWith("test", ignoreCase = true)) {
        InfoTooltipField(
            value = currentVal,
            onValueChange = onValueChange,
            label = field.label,
            tooltipText = field.tooltip ?: field.label,
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = field.minLines,
        )
    } else {
        AutocompleteTextField(
            value = currentVal,
            onValueChange = onValueChange,
            label = field.label,
            tooltipText = field.tooltip ?: field.label,
            modifier = Modifier.fillMaxWidth(),
            minLines = field.minLines,
            singleLine = field.singleLine,
            maxLength = field.maxLength,
            availableVariables = availableVariables,
            insertRawVariable = false,
        )
    }
}

@Composable
private fun NodeConfigErrorBanner(
    errorMessage: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun SubtleSectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        thickness = 1.dp,
    )
}

@Composable
private fun SectionTitleWithInfo(
    title: String,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
    subtitle: String? = null,
) {
    var showTooltip by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
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

            if (!tooltip.isNullOrBlank()) {
                Box {
                    IconButton(
                        onClick = { showTooltip = !showTooltip },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info for $title",
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
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tooltip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NodeConfigDynamicSchemaSections(
    pluginManifest: dev.zophop.plugin.api.NodePluginManifest?,
    configStateMap: Map<String, String>,
    availableVariables: List<String>,
    onFieldChange: (String, String) -> Unit,
    selectedCredential: Credential? = null,
) {
    if (pluginManifest == null || pluginManifest.configSchema.isEmpty()) return

    val schema = pluginManifest.configSchema
    val mainFields = schema.filter { !it.isTestField && !it.key.startsWith("test", ignoreCase = true) }
    val testFields = schema.filter { it.isTestField || it.key.startsWith("test", ignoreCase = true) }

    if (mainFields.isNotEmpty()) {
        SubtleSectionDivider()
        NodeConfigMainFieldsSection(
            mainFields = mainFields,
            configStateMap = configStateMap,
            availableVariables = availableVariables,
            onFieldChange = onFieldChange,
            selectedCredential = selectedCredential,
        )
    }

    if (testFields.isNotEmpty()) {
        SubtleSectionDivider()
        NodeConfigTestFieldsSection(
            testFields = testFields,
            configStateMap = configStateMap,
            availableVariables = availableVariables,
            onFieldChange = onFieldChange,
        )
    }
}

@Composable
private fun NodeConfigMainFieldsSection(
    mainFields: List<dev.zophop.plugin.api.ConfigFieldSpec>,
    configStateMap: Map<String, String>,
    availableVariables: List<String>,
    onFieldChange: (String, String) -> Unit,
    selectedCredential: Credential? = null,
) {
    if (mainFields.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitleWithInfo(
            title = "⚙️ Node Configuration",
            tooltip = "Configure the parameters and inputs for this step. Tap variable chips below any field to insert workflow variables.",
//            subtitle = if (availableVariables.isNotEmpty()) "${availableVariables.size} upstream variables available" else null,
        )
        mainFields.forEach { field ->
            RenderDynamicConfigField(
                field = field,
                currentVal = configStateMap[field.key] ?: "",
                onValueChange = { newValue -> onFieldChange(field.key, newValue) },
                availableVariables = availableVariables,
                selectedCredential = selectedCredential,
            )
        }
    }
}

@Composable
private fun NodeConfigPermissionBanner(
    permissionSummary: String,
    onRequestPermission: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        text = permissionSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "Required to activate this step",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }
            }
            FilledTonalButton(
                onClick = onRequestPermission,
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
            ) {
                Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NodeConfigCredentialSelector(
    credentials: List<Credential>,
    credentialId: String,
    onCredentialSelected: (Credential?) -> Unit,
    onCreateNewClick: () -> Unit,
) {
    val selectedCred = credentials.find { it.id == credentialId }
    val credName = selectedCred?.let { "🔑 ${it.name} (${it.type})" } ?: "None (Optional Credential)"

    SearchableDropdownSelector(
        selectedItemText = credName,
        label = "🔑 Attach Saved Credential",
        searchPlaceholder = "Search credentials...",
        items = credentials,
        itemText = { cred -> "${cred.name} (${cred.type})" },
        onItemSelected = { cred -> onCredentialSelected(cred) },
        isItemSelected = { cred -> cred.id == credentialId },
        showNoneOption = true,
        noneOptionText = "None",
        onCreateNewClick = onCreateNewClick,
        createNewText = "➕ Create New Credential...",
        tooltipText = "Attach saved vault credentials for authenticated server requests.",
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun fillMissingDropdownDefaults(
    manifest: dev.zophop.plugin.api.NodePluginManifest,
    currentMap: Map<String, String>,
): Map<String, String> {
    val updatedMap = currentMap.toMutableMap()
    var changed = false
    manifest.configSchema.forEach { field ->
        if (field is dev.zophop.plugin.api.ConfigFieldSpec.DropdownField && field.options.isNotEmpty()) {
            val current = updatedMap[field.key]?.trim().orEmpty()
            if (current.isBlank()) {
                updatedMap[field.key] = field.options.first().first
                changed = true
            }
        }
    }
    return if (changed) updatedMap else currentMap
}

private fun findInitialAttachedVaultPrecIds(
    canShowGuard: Boolean,
    vaultPreconditions: List<PreconditionEntity>,
    nodePreconditions: List<dev.zophop.domain.model.Precondition>,
): Set<String> {
    if (!canShowGuard) return emptySet()
    return vaultPreconditions.filter { prec ->
        nodePreconditions.any { it.id == prec.id || it.type.name == prec.type }
    }.map { it.id }.toSet()
}

private fun isCredentialSupported(
    nodeType: String,
    manifest: dev.zophop.plugin.api.NodePluginManifest?,
): Boolean {
    val resolved = manifest ?: dev.zophop.domain.engine.plugin.BuiltInPluginRegistry.getBuiltInManifestByTypeId(nodeType)
    return resolved?.isCredentialSupported() ?: false
}

@Composable
private fun NodeConfigBottomBar(
    onDismiss: () -> Unit,
    onSaveClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onSaveClick) {
                Text("Save Node")
            }
        }
    }
}

private fun loadInitialNodeConfig(
    nodeType: String,
    configJson: String,
): Map<String, String> {
    val map = mutableMapOf<String, String>()
    try {
        val defaultConfigStr = WorkflowUiHelper.getDefaultConfigForType(nodeType)
        val defaultJson = JSONObject(defaultConfigStr)
        val defaultKeys = defaultJson.keys()
        while (defaultKeys.hasNext()) {
            val k = defaultKeys.next()
            map[k] = defaultJson.optString(k, "")
        }
    } catch (_: Exception) {
    }

    try {
        val json = JSONObject(configJson)
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = json.optString(k, "")
            if (v.isNotBlank()) {
                map[k] = v
            }
        }
    } catch (_: Exception) {
    }
    return map
}

private fun buildAndValidateNodeConfig(
    nodeType: String,
    name: String,
    configStateMap: Map<String, String>,
    credentialId: String,
    pluginManifest: dev.zophop.plugin.api.NodePluginManifest?,
    unifiedRegistry: dev.zophop.domain.engine.plugin.UnifiedPluginRegistry,
): Pair<String?, String> {
    if (name.isBlank()) {
        return "Node name cannot be blank." to ""
    }
    val configMapForValidation = configStateMap.toMutableMap()
    pluginManifest?.configSchema?.forEach { field ->
        if (field is dev.zophop.plugin.api.ConfigFieldSpec.DropdownField && field.options.isNotEmpty()) {
            if (configMapForValidation[field.key].isNullOrBlank()) {
                configMapForValidation[field.key] = field.options.first().first
            }
        }
    }
    applyDefaultConfigValues(nodeType, configMapForValidation, pluginManifest)
    configMapForValidation["credentialId"] = credentialId

    val validationJson =
        JSONObject().apply {
            for ((k, v) in configMapForValidation) {
                put(k, v)
            }
        }.toString()
    val moduleError = unifiedRegistry.validateConfig(nodeType, validationJson)
    val validationError = moduleError ?: NodeConfigValidator.validateNodeConfig(nodeType, configMapForValidation, pluginManifest)
    if (validationError != null) {
        return validationError to ""
    }

    return null to validationJson
}

private fun applyDefaultConfigValues(
    nodeType: String,
    map: MutableMap<String, String>,
    manifest: dev.zophop.plugin.api.NodePluginManifest? = null,
) {
    try {
        val defaults = JSONObject(WorkflowUiHelper.getDefaultConfigForType(nodeType, manifest))
        val dKeys = defaults.keys()
        while (dKeys.hasNext()) {
            val dk = dKeys.next()
            val dv = defaults.optString(dk, "")
            if (map[dk].isNullOrBlank() && dv.isNotBlank()) {
                map[dk] = dv
            }
        }
    } catch (_: Exception) {
    }
}

private fun isPermissionGranted(grantMap: Map<String, Boolean>): Boolean {
    val hasLocation = grantMap.containsKey(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
        grantMap.containsKey(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    if (hasLocation) {
        val locationSatisfied = (grantMap[android.Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
            (grantMap[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true)
        val otherPermsGranted = grantMap.filterKeys {
            it != android.Manifest.permission.ACCESS_FINE_LOCATION && it != android.Manifest.permission.ACCESS_COARSE_LOCATION
        }.values.all { it }
        return locationSatisfied && otherPermsGranted
    }
    return grantMap.isNotEmpty() && grantMap.values.all { it }
}

@Composable
private fun NodeConfigTestFieldsSection(
    testFields: List<dev.zophop.plugin.api.ConfigFieldSpec>,
    configStateMap: Map<String, String>,
    availableVariables: List<String>,
    onFieldChange: (String, String) -> Unit,
) {
    var isTestSectionExpanded by remember { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { isTestSectionExpanded = !isTestSectionExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitleWithInfo(
                    title = "🧪 Test Simulation Payload",
                    tooltip = "Mock input data used specifically for dry-running and testing this workflow on demand.",
                    subtitle = "Test & dry-run inputs",
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { isTestSectionExpanded = !isTestSectionExpanded },
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = if (isTestSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isTestSectionExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(visible = isTestSectionExpanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    testFields.forEach { field ->
                        RenderDynamicConfigField(
                            field = field,
                            currentVal = configStateMap[field.key] ?: "",
                            onValueChange = { newValue -> onFieldChange(field.key, newValue) },
                            availableVariables = availableVariables,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeConfigPreconditionsSection(
    attachedVaultPrecIds: Set<String>,
    vaultPreconditions: List<PreconditionEntity>,
    onAttachedIdsChange: (Set<String>) -> Unit,
    onRequestCreatePrecondition: ((onCreated: (PreconditionEntity) -> Unit) -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionTitleWithInfo(
            title = "🛡️ Precondition Rules Guard",
            tooltip = "Attach saved vault precondition rules that must pass before this node executes.",
        )

        if (attachedVaultPrecIds.isNotEmpty()) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    attachedVaultPrecIds.forEach { precId ->
                        val vaultPrec = vaultPreconditions.find { it.id == precId }
                        if (vaultPrec != null) {
                            val isEnabled = vaultPrec.isEnabled
                            InputChip(
                                selected = true,
                                onClick = { onAttachedIdsChange(attachedVaultPrecIds - precId) },
                                label = {
                                    Text(
                                        text = vaultPrec.name + if (!isEnabled) " (Disabled)" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                leadingIcon = {
                                    if (vaultPrec.icon.isNotBlank()) {
                                        Text(
                                            text = vaultPrec.icon,
                                            fontSize = 12.sp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove ${vaultPrec.name}",
                                        modifier = Modifier.size(12.dp),
                                    )
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors =
                                    InputChipDefaults.inputChipColors(
                                        selectedContainerColor =
                                            if (isEnabled) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        selectedLabelColor =
                                            if (isEnabled) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                        selectedLeadingIconColor =
                                            if (isEnabled) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                        selectedTrailingIconColor =
                                            if (isEnabled) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            },
                                    ),
                                border = null,
                                modifier = Modifier.height(28.dp),
                            )
                        }
                    }
                }
            }
        }

        SearchableDropdownSelector(
            selectedItemText = "Select Precondition Rules",
            label = "Select Preconditions",
            searchPlaceholder = "Search preconditions...",
            items = vaultPreconditions.filter { it.isEnabled },
            itemText = { prec ->
                val iconPrefix = if (prec.icon.isNotBlank()) "${prec.icon} " else ""
                val summary = WorkflowUiHelper.getPreconditionPipelineSummary(prec.type, prec.pipelineJson).second
                "$iconPrefix${prec.name} ($summary)"
            },
            onItemSelected = { prec ->
                if (prec != null) {
                    onAttachedIdsChange(
                        if (attachedVaultPrecIds.contains(prec.id)) attachedVaultPrecIds - prec.id else attachedVaultPrecIds + prec.id,
                    )
                }
            },
            isItemSelected = { prec -> attachedVaultPrecIds.contains(prec.id) },
            isMultiSelect = true,
            showNoneOption = false,
            onCreateNewClick = {
                onRequestCreatePrecondition?.invoke { newPrec ->
                    onAttachedIdsChange(attachedVaultPrecIds + newPrec.id)
                }
            },
            createNewText = "Add New",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
