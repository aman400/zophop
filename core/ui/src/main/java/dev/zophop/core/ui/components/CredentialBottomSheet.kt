package dev.zophop.core.ui.components

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.CredentialType

@Composable
fun CredentialBottomSheet(
    credential: Credential?,
    linkedWorkflowCount: Int = 0,
    linkedPreconditionCount: Int = 0,
    isLockEnabled: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Credential) -> Unit,
) {
    val context = LocalContext.current
    val specRegistry =
        remember {
            dev.zophop.domain.engine.plugin
                .CredentialSpecRegistry(context)
        }
    var credentialSpecs by remember { mutableStateOf<List<dev.zophop.plugin.api.CredentialSpec>>(emptyList()) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        credentialSpecs = specRegistry.getAllCredentialSpecs()
    }

    NoSwipeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        var name by remember { mutableStateOf(credential?.name ?: "") }
        var selectedTypeId by remember { mutableStateOf(credential?.type ?: CredentialType.BEARER_TOKEN) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        var credentialDataMap by remember { mutableStateOf(credential?.dataMap ?: emptyMap()) }
        val activeSpec = credentialSpecs.find { it.typeId == selectedTypeId }

        var showEditConfirmationDialog by remember { mutableStateOf(false) }
        var pendingSavedCredential by remember { mutableStateOf<Credential?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (credential == null) "New Credential" else "Edit Credential") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                )
            },
            bottomBar = {
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
                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    errorMessage = "Credential name cannot be blank."
                                    return@Button
                                }
                                errorMessage = null
                                val newCred =
                                    Credential(
                                        id =
                                            credential?.id ?: java.util.UUID
                                                .randomUUID()
                                                .toString(),
                                        name = name,
                                        type = selectedTypeId,
                                        dataMap = credentialDataMap,
                                    )
                                if (credential != null && (linkedWorkflowCount > 0 || linkedPreconditionCount > 0)) {
                                    pendingSavedCredential = newCred
                                    showEditConfirmationDialog = true
                                } else {
                                    onSave(newCred)
                                }
                            },
                        ) {
                            Text(if (credential == null) "Create Credential" else "Save Changes")
                        }
                    }
                }
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp)
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))
                errorMessage?.let { err ->
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
                                text = err,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                }

                InfoTooltipField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Credential Name",
                    tooltipText = "Unique display name for identifying this set of authentication credentials in your vault.",
                    modifier = Modifier.fillMaxWidth(),
                )

                if (credential != null) {
                    CredentialUsageBadges(
                        linkedWorkflowCount = linkedWorkflowCount,
                        linkedPreconditionCount = linkedPreconditionCount,
                    )
                }

                CredentialTypeSelector(
                    selectedTypeId = selectedTypeId,
                    activeSpec = activeSpec,
                    credentialSpecs = credentialSpecs,
                    onTypeSelected = { selectedTypeId = it },
                )

                if (activeSpec != null) {
                    CredentialFieldsList(
                        activeSpec = activeSpec,
                        credentialDataMap = credentialDataMap,
                        isLockEnabled = isLockEnabled,
                        onDataChanged = { credentialDataMap = it },
                    )
                }

                Spacer(modifier = Modifier.height(36.dp))
            }
        }

        if (showEditConfirmationDialog && pendingSavedCredential != null) {
            AlertDialog(
                onDismissRequest = { showEditConfirmationDialog = false },
                title = { Text("Update Credential In Use?") },
                text = {
                    Text(
                        "This credential is currently used by $linkedWorkflowCount workflow(s) and $linkedPreconditionCount precondition(s). Updating its authentication details will immediately affect their execution behavior.",
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            pendingSavedCredential?.let { onSave(it) }
                            showEditConfirmationDialog = false
                        },
                    ) {
                        Text("Save Changes")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showEditConfirmationDialog = false
                        },
                    ) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun CredentialUsageBadges(
    linkedWorkflowCount: Int,
    linkedPreconditionCount: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val wfBg = if (linkedWorkflowCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        val wfFg = if (linkedWorkflowCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Surface(color = wfBg, contentColor = wfFg, shape = RoundedCornerShape(6.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(13.dp))
                Text(
                    text = if (linkedWorkflowCount > 0) "$linkedWorkflowCount Linked Workflow${if (linkedWorkflowCount > 1) "s" else ""}" else "0 Linked Workflows",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        val pcBg = if (linkedPreconditionCount > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        val pcFg = if (linkedPreconditionCount > 0) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        Surface(color = pcBg, contentColor = pcFg, shape = RoundedCornerShape(6.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(13.dp))
                Text(
                    text = if (linkedPreconditionCount > 0) "$linkedPreconditionCount Linked Precondition${if (linkedPreconditionCount > 1) "s" else ""}" else "0 Linked Preconditions",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CredentialTypeSelector(
    selectedTypeId: String,
    activeSpec: dev.zophop.plugin.api.CredentialSpec?,
    credentialSpecs: List<dev.zophop.plugin.api.CredentialSpec>,
    onTypeSelected: (String) -> Unit,
) {
    var expandedTypeMenu by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expandedTypeMenu,
        onExpandedChange = { expandedTypeMenu = !expandedTypeMenu },
    ) {
        val activeSpecLabel = activeSpec?.displayName ?: selectedTypeId
        OutlinedTextField(
            value = activeSpecLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Credential Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTypeMenu) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )

        ExposedDropdownMenu(
            expanded = expandedTypeMenu,
            onDismissRequest = { expandedTypeMenu = false },
        ) {
            credentialSpecs.forEach { spec ->
                DropdownMenuItem(
                    text = { Text(spec.displayName) },
                    onClick = {
                        onTypeSelected(spec.typeId)
                        expandedTypeMenu = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CredentialFieldsList(
    activeSpec: dev.zophop.plugin.api.CredentialSpec,
    credentialDataMap: Map<String, String>,
    isLockEnabled: Boolean,
    onDataChanged: (Map<String, String>) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        activeSpec.fields.forEach { field ->
            val currentVal = credentialDataMap[field.key] ?: ""
            when (field) {
                is dev.zophop.plugin.api.ConfigFieldSpec.TextField -> {
                    val isSensitive = field.isSecret ||
                        field.key.contains("token", true) ||
                        field.key.contains("password", true) ||
                        field.key.contains("secret", true) ||
                        field.key.contains("apiKey", true)
                    if (isSensitive) {
                        SensitiveTextField(
                            value = currentVal,
                            onValueChange = { newValue -> onDataChanged(credentialDataMap + (field.key to newValue)) },
                            label = field.label,
                            tooltipText = field.tooltip ?: field.label,
                            isLockEnabled = isLockEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        InfoTooltipField(
                            value = currentVal,
                            onValueChange = { newValue -> onDataChanged(credentialDataMap + (field.key to newValue)) },
                            label = field.label,
                            tooltipText = field.tooltip ?: field.label,
                            placeholder = field.placeholder,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = field.singleLine,
                            minLines = field.minLines,
                        )
                    }
                }
                is dev.zophop.plugin.api.ConfigFieldSpec.CodeTemplateField -> {
                    HeaderInputEditor(
                        headersString = currentVal,
                        onHeadersChanged = { newValue -> onDataChanged(credentialDataMap + (field.key to newValue)) },
                    )
                }
                is dev.zophop.plugin.api.ConfigFieldSpec.DropdownField -> {}
            }
        }
    }
}

@Composable
fun SensitiveTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tooltipText: String,
    isLockEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var passwordVisible by remember { mutableStateOf(false) }
    var showTooltip by remember { mutableStateOf(false) }
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

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { updated ->
                lastEmittedValue = updated
                if (updated != currentValue) {
                    currentOnValueChange(updated)
                }
            }
    }

    fun requestUnlock() {
        val authenticators = BiometricAuthHelper.allowedAuthenticators()
        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(authenticators)
        if (BiometricAuthHelper.canBypassWhenNoLockEnrolled(context)) {
            passwordVisible = true
            return
        }
        val activity = context as? FragmentActivity
        if (activity != null && canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt =
                BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            passwordVisible = true
                        }
                    },
                )
            val promptInfo =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Unlock Vault Credential")
                    .setSubtitle("Authenticate using biometrics or device PIN/pattern to view secret password")
                    .setAllowedAuthenticators(authenticators)
                    .build()

            prompt.authenticate(promptInfo)
        }
    }

    OutlinedSecureTextField(
        state = textFieldState,
        label = { Text(label) },
        modifier = modifier,
        textObfuscationMode =
            if (passwordVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
            ),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (passwordVisible) {
                            passwordVisible = false
                        } else {
                            if (isLockEnabled) {
                                requestUnlock()
                            } else {
                                passwordVisible = true
                            }
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle Password Visibility",
                    )
                }
                Box {
                    IconButton(onClick = { showTooltip = !showTooltip }) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Field Information",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showTooltip,
                        onDismissRequest = { showTooltip = false },
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .widthIn(max = 260.dp)
                                    .padding(12.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
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
