package dev.zophop.feature.backup

import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun ExportBackupDialog(
    backupService: BackupService,
    onDismiss: () -> Unit,
    isBiometricLockEnabled: Boolean = false,
    workflowsCount: Int? = null,
    credentialsCount: Int? = null,
    preconditionsCount: Int? = null,
    onExportFinished: (String) -> Unit = {},
) {
    ExportBackupBottomSheet(
        backupService = backupService,
        onDismiss = onDismiss,
        isBiometricLockEnabled = isBiometricLockEnabled,
        workflowsCount = workflowsCount,
        credentialsCount = credentialsCount,
        preconditionsCount = preconditionsCount,
        onExportFinished = onExportFinished,
    )
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun ExportBackupBottomSheet(
    backupService: BackupService,
    onDismiss: () -> Unit,
    isBiometricLockEnabled: Boolean = false,
    workflowsCount: Int? = null,
    credentialsCount: Int? = null,
    preconditionsCount: Int? = null,
    onExportFinished: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val requiresBiometric = isBiometricLockEnabled || backupService.isBiometricLockEnabled()
    var isBiometricAuthenticated by remember { mutableStateOf(!requiresBiometric) }
    var biometricAuthError by remember { mutableStateOf<String?>(null) }

    fun requestBiometricAuth() {
        requestBiometricUnlock(
            context = context,
            title = "Authenticate to Export Backup",
            subtitle = "Verify your biometric or device lock to proceed with export",
            onSuccess = {
                isBiometricAuthenticated = true
                biometricAuthError = null
            },
            onError = { error ->
                biometricAuthError = error
            },
        )
    }

    LaunchedEffect(requiresBiometric) {
        if (requiresBiometric && !isBiometricAuthenticated) {
            requestBiometricAuth()
        }
    }

    if (!isBiometricAuthenticated) {
        BiometricLockRequiredBottomSheet(
            title = "Export Backup Locked",
            subtitle = "Biometric or device authentication is enabled for your security. Please authenticate to export your workflows and secrets.",
            errorMessage = biometricAuthError,
            onUnlock = { requestBiometricAuth() },
            onDismiss = onDismiss,
        )
        return
    }

    var counts by remember {
        mutableStateOf(
            BackupCounts(
                workflowsCount = workflowsCount ?: 0,
                credentialsCount = credentialsCount ?: 0,
                preconditionsCount = preconditionsCount ?: 0,
            ),
        )
    }

    var availableWorkflows by remember { mutableStateOf<List<Workflow>>(emptyList()) }
    var availableCredentials by remember { mutableStateOf<List<Credential>>(emptyList()) }
    var availablePreconditions by remember { mutableStateOf<List<PreconditionEntity>>(emptyList()) }
    var hasInitializedWorkflows by remember { mutableStateOf(false) }

    var selectedWorkflowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var autoIncludeDependencies by remember { mutableStateOf(true) }
    var includeStandaloneCredentials by remember { mutableStateOf(false) }
    var includeStandalonePreconditions by remember { mutableStateOf(false) }

    LaunchedEffect(backupService) {
        counts = backupService.getAvailableCounts()
        availableWorkflows = backupService.getAvailableWorkflows()
        availableCredentials = backupService.getAvailableCredentials()
        availablePreconditions = backupService.getAvailablePreconditions()
        if (!hasInitializedWorkflows && availableWorkflows.isNotEmpty()) {
            selectedWorkflowIds = availableWorkflows.map { it.id }.toSet()
            hasInitializedWorkflows = true
        }
    }

    LaunchedEffect(availableWorkflows) {
        if (!hasInitializedWorkflows && availableWorkflows.isNotEmpty()) {
            selectedWorkflowIds = availableWorkflows.map { it.id }.toSet()
            hasInitializedWorkflows = true
        }
    }

    val relatedDependencies = remember(selectedWorkflowIds, availableWorkflows, availablePreconditions) {
        BackupDependencyResolver.resolveWorkflowDependencies(
            selectedWorkflowIds = selectedWorkflowIds,
            availableWorkflows = availableWorkflows,
            availablePreconditions = availablePreconditions,
        )
    }

    var encryptSecrets by remember { mutableStateOf(false) }
    var pinOrPassword by remember { mutableStateOf("") }
    var confirmPinOrPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isGenerating by remember { mutableStateOf(false) }

    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val json = pendingExportJson
            if (uri != null && json != null) {
                saveExportJsonToUri(
                    context = context,
                    uri = uri,
                    json = json,
                    onSuccess = {
                        Toast.makeText(context, "Backup exported successfully", Toast.LENGTH_SHORT).show()
                        onExportFinished(json)
                        onDismiss()
                    },
                    onFailure = { msg ->
                        Toast.makeText(context, "Export failed: $msg", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }

    fun handleExport(action: (String) -> Unit) {
        val (options, validationError) =
            validateExportOptions(
                selectedWorkflowIds = selectedWorkflowIds,
                autoIncludeDependencies = autoIncludeDependencies,
                includeStandaloneCredentials = includeStandaloneCredentials,
                includeStandalonePreconditions = includeStandalonePreconditions,
                hasDependencies = relatedDependencies.credentialIds.isNotEmpty() ||
                    relatedDependencies.preconditionIds.isNotEmpty(),
                encryptSecrets = encryptSecrets,
                pinOrPassword = pinOrPassword,
                confirmPinOrPassword = confirmPinOrPassword,
            )
        errorMessage = validationError
        if (options == null) return

        isGenerating = true
        coroutineScope.launch {
            try {
                val json = backupService.exportBackup(options)
                pendingExportJson = json
                isGenerating = false
                action(json)
            } catch (e: IOException) {
                isGenerating = false
                errorMessage = "Export generation failed: ${e.message}"
            } catch (e: SecurityException) {
                isGenerating = false
                errorMessage = "Export generation failed: ${e.message}"
            } catch (e: IllegalArgumentException) {
                isGenerating = false
                errorMessage = "Export generation failed: ${e.message}"
            }
        }
    }

    val effectiveCredentialsCount = if (autoIncludeDependencies) {
        val combined = relatedDependencies.credentialIds +
            (if (includeStandaloneCredentials) availableCredentials.map { it.id }.toSet() else emptySet())
        availableCredentials.count { it.id in combined }
    } else if (includeStandaloneCredentials) {
        availableCredentials.size
    } else {
        0
    }

    val effectivePreconditionsCount = if (autoIncludeDependencies) {
        val combined = relatedDependencies.preconditionIds +
            (if (includeStandalonePreconditions) availablePreconditions.map { it.id }.toSet() else emptySet())
        availablePreconditions.count { it.id in combined }
    } else if (includeStandalonePreconditions) {
        availablePreconditions.size
    } else {
        0
    }

    val totalSelected = selectedWorkflowIds.size + effectiveCredentialsCount + effectivePreconditionsCount

    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BackupBottomSheetHeader(
                title = "Export Backup",
                subtitle = "Choose workflows to export (related credentials & rules auto-included)",
                icon = Icons.Default.Upload,
                count = totalSelected,
                onCloseClick = onDismiss,
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Select individual workflows to export. Referenced credentials and preconditions are automatically bundled with the selection.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                ExportWorkflowsSelectionSection(
                    workflows = availableWorkflows,
                    selectedWorkflowIds = selectedWorkflowIds,
                    onToggleWorkflow = { id -> selectedWorkflowIds = toggleId(selectedWorkflowIds, id) },
                    onToggleAll = { selectAll ->
                        selectedWorkflowIds = toggleAll(selectAll, availableWorkflows.map { it.id })
                    },
                )

                if (selectedWorkflowIds.isNotEmpty() &&
                    (relatedDependencies.credentialIds.isNotEmpty() || relatedDependencies.preconditionIds.isNotEmpty())
                ) {
                    ExportAutoDependenciesCard(
                        relatedCredCount = relatedDependencies.credentialIds.size,
                        relatedPrecCount = relatedDependencies.preconditionIds.size,
                        autoInclude = autoIncludeDependencies,
                        onToggleAutoInclude = { autoIncludeDependencies = it },
                    )
                }

                ExportAdditionalItemsSection(
                    availableCredentialsCount = availableCredentials.size,
                    includeStandaloneCredentials = includeStandaloneCredentials,
                    onToggleStandaloneCredentials = { includeStandaloneCredentials = it },
                    availablePreconditionsCount = availablePreconditions.size,
                    includeStandalonePreconditions = includeStandalonePreconditions,
                    onToggleStandalonePreconditions = { includeStandalonePreconditions = it },
                )

                ExportPasswordCard(
                    encryptSecrets = encryptSecrets,
                    onToggleEncrypt = {
                        encryptSecrets = it
                        errorMessage = null
                    },
                    pinOrPassword = pinOrPassword,
                    onPasswordChange = {
                        pinOrPassword = it
                        errorMessage = null
                    },
                    confirmPinOrPassword = confirmPinOrPassword,
                    onConfirmChange = {
                        confirmPinOrPassword = it
                        errorMessage = null
                    },
                    showPassword = showPassword,
                    onToggleShowPassword = { showPassword = !showPassword },
                )

                if (errorMessage != null) {
                    ImportErrorBanner(errorMessage = errorMessage!!)
                }

                if (isGenerating) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Generating backup...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        handleExport {
                            createDocumentLauncher.launch("zophop_backup_${System.currentTimeMillis()}.json")
                        }
                    },
                    enabled = !isGenerating && totalSelected > 0,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save to File", fontWeight = FontWeight.SemiBold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            handleExport { json ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("ZopHop Backup", json))
                                Toast.makeText(context, "Backup JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                                onExportFinished(json)
                                onDismiss()
                            }
                        },
                        enabled = !isGenerating && totalSelected > 0,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }

                    OutlinedButton(
                        onClick = {
                            handleExport { json ->
                                val intent =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_TEXT, json)
                                        putExtra(Intent.EXTRA_TITLE, "ZopHop Backup")
                                    }
                                context.startActivity(Intent.createChooser(intent, "Share ZopHop Backup"))
                                onExportFinished(json)
                                onDismiss()
                            }
                        },
                        enabled = !isGenerating && totalSelected > 0,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            }
        }
    }
}

@Composable
fun ImportBackupDialog(
    backupService: BackupService,
    onDismiss: () -> Unit,
    isBiometricLockEnabled: Boolean = false,
    onImportFinished: (ImportResult) -> Unit = {},
) {
    ImportBackupBottomSheet(
        backupService = backupService,
        onDismiss = onDismiss,
        isBiometricLockEnabled = isBiometricLockEnabled,
        onImportFinished = onImportFinished,
    )
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun ImportBackupBottomSheet(
    backupService: BackupService,
    onDismiss: () -> Unit,
    isBiometricLockEnabled: Boolean = false,
    onImportFinished: (ImportResult) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val requiresBiometric = isBiometricLockEnabled || backupService.isBiometricLockEnabled()
    var isBiometricAuthenticated by remember { mutableStateOf(!requiresBiometric) }
    var biometricAuthError by remember { mutableStateOf<String?>(null) }

    fun requestBiometricAuth() {
        requestBiometricUnlock(
            context = context,
            title = "Authenticate to Import Backup",
            subtitle = "Verify your biometric or device lock to restore workflows and secrets",
            onSuccess = {
                isBiometricAuthenticated = true
                biometricAuthError = null
            },
            onError = { error ->
                biometricAuthError = error
            },
        )
    }

    LaunchedEffect(requiresBiometric) {
        if (requiresBiometric && !isBiometricAuthenticated) {
            requestBiometricAuth()
        }
    }

    if (!isBiometricAuthenticated) {
        BiometricLockRequiredBottomSheet(
            title = "Import Backup Locked",
            subtitle = "Biometric or device authentication is enabled for your security. Please authenticate to import and restore workflows and credentials.",
            errorMessage = biometricAuthError,
            onUnlock = { requestBiometricAuth() },
            onDismiss = onDismiss,
        )
        return
    }

    var parsedData by remember { mutableStateOf<ParsedBackupData?>(null) }
    var unlockPassword by remember { mutableStateOf("") }
    var isUnlocked by remember { mutableStateOf(false) }
    var showUnlockPassword by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    var selectedWorkflowIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedCredentialIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPreconditionIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var conflictStrategy by remember { mutableStateOf(ImportConflictStrategy.OVERWRITE) }
    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    fun syncDependenciesForWorkflows(newSelectedWfIds: Set<String>, data: ParsedBackupData) {
        val related = BackupDependencyResolver.resolveWorkflowDependencies(
            selectedWorkflowIds = newSelectedWfIds,
            availableWorkflows = data.workflows,
            availablePreconditions = data.preconditions,
        )
        selectedCredentialIds = selectedCredentialIds + related.credentialIds
        selectedPreconditionIds = selectedPreconditionIds + related.preconditionIds
    }

    fun processImportJson(text: String?) {
        if (text.isNullOrBlank()) {
            parseError = "No backup content found."
            return
        }
        coroutineScope.launch {
            try {
                parseError = null
                val parsed = backupService.parseImport(text)
                parsedData = parsed
                isUnlocked = !parsed.isEncrypted
                val allWfIds = parsed.workflows.map { it.id }.toSet()
                selectedWorkflowIds = allWfIds
                selectedCredentialIds = parsed.credentials.map { it.id }.toSet()
                selectedPreconditionIds = parsed.preconditions.map { it.id }.toSet()
            } catch (e: IllegalArgumentException) {
                parseError = "Invalid backup format: ${e.message}"
            }
        }
    }

    val openDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                processImportJson(readTextFromUri(context, uri))
            }
        }

    fun handleUnlockSecrets() {
        val currentParsed = parsedData ?: return
        if (unlockPassword.isBlank()) {
            passwordError = "Enter your PIN or password."
            return
        }
        coroutineScope.launch {
            try {
                val decrypted = backupService.decryptImport(currentParsed, unlockPassword)
                parsedData = decrypted
                isUnlocked = true
                passwordError = null
                val allWfIds = decrypted.workflows.map { it.id }.toSet()
                selectedWorkflowIds = allWfIds
                selectedCredentialIds = decrypted.credentials.map { it.id }.toSet()
                selectedPreconditionIds = decrypted.preconditions.map { it.id }.toSet()
            } catch (_: SecurityException) {
                passwordError = "Incorrect PIN or password."
            } catch (e: IllegalArgumentException) {
                passwordError = "Decryption error: ${e.message}"
            }
        }
    }

    fun handleExecuteImport() {
        val currentParsed = parsedData ?: return
        isImporting = true
        coroutineScope.launch {
            try {
                val filteredData =
                    currentParsed.copy(
                        workflows = currentParsed.workflows.filter { it.id in selectedWorkflowIds },
                        credentials = currentParsed.credentials.filter { it.id in selectedCredentialIds },
                        preconditions = currentParsed.preconditions.filter { it.id in selectedPreconditionIds },
                    )
                val result = backupService.executeImport(filteredData, conflictStrategy)
                importResult = result
                isImporting = false
            } catch (e: IOException) {
                isImporting = false
                parseError = "Import failed: ${e.message}"
            } catch (e: IllegalArgumentException) {
                isImporting = false
                parseError = "Import failed: ${e.message}"
            }
        }
    }

    if (importResult != null) {
        ImportCompleteBottomSheet(
            result = importResult!!,
            onDismiss = {
                onImportFinished(importResult!!)
                onDismiss()
            },
        )
        return
    }

    val totalSelected = selectedWorkflowIds.size + selectedCredentialIds.size + selectedPreconditionIds.size

    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BackupBottomSheetHeader(
                title = if (parsedData == null) "Import Backup" else "Review & Import",
                subtitle = if (parsedData == null) {
                    "Restore workflows, credentials & rules"
                } else {
                    "$totalSelected items selected for import"
                },
                icon = Icons.Default.Download,
                count = if (parsedData != null) totalSelected else null,
                onCloseClick = onDismiss,
            )

            ImportBottomSheetBody(
                modifier = Modifier.weight(1f, fill = false),
                parsedData = parsedData,
                isUnlocked = isUnlocked,
                unlockPassword = unlockPassword,
                onPasswordChange = {
                    unlockPassword = it
                    passwordError = null
                },
                showUnlockPassword = showUnlockPassword,
                onToggleShowPassword = { showUnlockPassword = !showUnlockPassword },
                passwordError = passwordError,
                onUnlock = { handleUnlockSecrets() },
                selectedWorkflowIds = selectedWorkflowIds,
                onToggleWorkflow = { id ->
                    val newSelected = toggleId(selectedWorkflowIds, id)
                    selectedWorkflowIds = newSelected
                    parsedData?.let { syncDependenciesForWorkflows(newSelected, it) }
                },
                onToggleAllWorkflows = { sel ->
                    val allWfs = parsedData?.workflows?.map { it.id }.orEmpty()
                    val newSelected = toggleAll(sel, allWfs)
                    selectedWorkflowIds = newSelected
                    if (sel && parsedData != null) {
                        syncDependenciesForWorkflows(newSelected, parsedData!!)
                    }
                },
                selectedCredentialIds = selectedCredentialIds,
                onToggleCredential = { id -> selectedCredentialIds = toggleId(selectedCredentialIds, id) },
                onToggleAllCredentials = { sel ->
                    selectedCredentialIds = toggleAll(sel, parsedData?.credentials?.map { it.id }.orEmpty())
                },
                selectedPreconditionIds = selectedPreconditionIds,
                onTogglePrecondition = { id -> selectedPreconditionIds = toggleId(selectedPreconditionIds, id) },
                onToggleAllPreconditions = { sel ->
                    selectedPreconditionIds = toggleAll(sel, parsedData?.preconditions?.map { it.id }.orEmpty())
                },
                conflictStrategy = conflictStrategy,
                onSelectConflictStrategy = { conflictStrategy = it },
                onPickFile = { openDocumentLauncher.launch(arrayOf("application/json", "text/*")) },
                onPaste = { processImportJson(readTextFromClipboard(context)) },
                parseError = parseError,
                isImporting = isImporting,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )

            ImportBottomSheetBottomBar(
                hasParsedData = parsedData != null,
                totalSelected = totalSelected,
                canImport = !isImporting && totalSelected > 0 && (parsedData?.isEncrypted != true || isUnlocked),
                onDismiss = onDismiss,
                onChangeFile = {
                    parsedData = null
                    parseError = null
                },
                onImport = { handleExecuteImport() },
            )
        }
    }
}

private fun toggleId(currentSet: Set<String>, id: String): Set<String> =
    if (id in currentSet) currentSet - id else currentSet + id

private fun toggleAll(selectAll: Boolean, allIds: List<String>): Set<String> =
    if (selectAll) allIds.toSet() else emptySet()

fun requestBiometricUnlock(
    context: Context,
    title: String,
    subtitle: String,
    onSuccess: () -> Unit,
    onError: (String) -> Unit = {},
) {
    val activity = context as? FragmentActivity
    if (activity != null) {
        try {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt =
                BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                            ) {
                                onError(errString.toString())
                            }
                        }
                    },
                )
            val promptInfo =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    ).build()

            prompt.authenticate(promptInfo)
        } catch (e: SecurityException) {
            onError(e.message ?: "Authentication failed")
        } catch (e: IllegalArgumentException) {
            onError(e.message ?: "Authentication failed")
        } catch (e: IllegalStateException) {
            onError(e.message ?: "Authentication failed")
        }
    } else {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager == null || !keyguardManager.isDeviceSecure) {
            onSuccess()
        } else {
            onError("FragmentActivity not available for biometric prompt")
        }
    }
}

@Composable
private fun BiometricLockRequiredBottomSheet(
    title: String,
    subtitle: String,
    errorMessage: String?,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = onUnlock,
                    modifier = Modifier.weight(1f).height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Unlock")
                }
            }
        }
    }
}

@Composable
private fun ExportWorkflowsSelectionSection(
    workflows: List<Workflow>,
    selectedWorkflowIds: Set<String>,
    onToggleWorkflow: (String) -> Unit,
    onToggleAll: (Boolean) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("Workflows", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = "${selectedWorkflowIds.size}/${workflows.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                if (workflows.isNotEmpty()) {
                    TextButton(
                        onClick = { onToggleAll(selectedWorkflowIds.size < workflows.size) },
                    ) {
                        Text(
                            text = if (selectedWorkflowIds.size == workflows.size) "Deselect All" else "Select All",
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp,
            )

            if (workflows.isEmpty()) {
                Text(
                    text = "No workflows created yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                workflows.forEach { wf ->
                    ImportItemRow(
                        title = wf.name,
                        subtitle = "${wf.nodes.size} nodes",
                        checked = wf.id in selectedWorkflowIds,
                        onToggle = { onToggleWorkflow(wf.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportAutoDependenciesCard(
    relatedCredCount: Int,
    relatedPrecCount: Int,
    autoInclude: Boolean,
    onToggleAutoInclude: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Link,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        "Auto-Include Dependencies",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Switch(
                    checked = autoInclude,
                    onCheckedChange = onToggleAutoInclude,
                    modifier = Modifier.size(width = 44.dp, height = 24.dp),
                )
            }

            Text(
                text = "Referenced credentials and rules required by selected workflows:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (relatedCredCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            text = "$relatedCredCount credentials linked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                if (relatedPrecCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Text(
                            text = "$relatedPrecCount precondition rules linked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportAdditionalItemsSection(
    availableCredentialsCount: Int,
    includeStandaloneCredentials: Boolean,
    onToggleStandaloneCredentials: (Boolean) -> Unit,
    availablePreconditionsCount: Int,
    includeStandalonePreconditions: Boolean,
    onToggleStandalonePreconditions: (Boolean) -> Unit,
) {
    if (availableCredentialsCount == 0 && availablePreconditionsCount == 0) return

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Additional Items (Optional)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )

            if (availableCredentialsCount > 0) {
                ImportItemRow(
                    title = "Include all vault credentials",
                    subtitle = "$availableCredentialsCount total credentials in vault",
                    checked = includeStandaloneCredentials,
                    onToggle = { onToggleStandaloneCredentials(!includeStandaloneCredentials) },
                )
            }

            if (availablePreconditionsCount > 0) {
                ImportItemRow(
                    title = "Include all precondition rules",
                    subtitle = "$availablePreconditionsCount total rules defined",
                    checked = includeStandalonePreconditions,
                    onToggle = { onToggleStandalonePreconditions(!includeStandalonePreconditions) },
                )
            }
        }
    }
}

@Composable
private fun ImportBottomSheetBody(
    modifier: Modifier = Modifier,
    parsedData: ParsedBackupData?,
    isUnlocked: Boolean,
    unlockPassword: String,
    onPasswordChange: (String) -> Unit,
    showUnlockPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    passwordError: String?,
    onUnlock: () -> Unit,
    selectedWorkflowIds: Set<String>,
    onToggleWorkflow: (String) -> Unit,
    onToggleAllWorkflows: (Boolean) -> Unit,
    selectedCredentialIds: Set<String>,
    onToggleCredential: (String) -> Unit,
    onToggleAllCredentials: (Boolean) -> Unit,
    selectedPreconditionIds: Set<String>,
    onTogglePrecondition: (String) -> Unit,
    onToggleAllPreconditions: (Boolean) -> Unit,
    conflictStrategy: ImportConflictStrategy,
    onSelectConflictStrategy: (ImportConflictStrategy) -> Unit,
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
    parseError: String?,
    isImporting: Boolean,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (parsedData == null) {
            ImportSourcePickers(
                onPickFile = onPickFile,
                onPaste = onPaste,
            )
        } else {
            ImportReviewContent(
                parsedData = parsedData,
                isUnlocked = isUnlocked,
                unlockPassword = unlockPassword,
                onPasswordChange = onPasswordChange,
                showUnlockPassword = showUnlockPassword,
                onToggleShowPassword = onToggleShowPassword,
                passwordError = passwordError,
                onUnlock = onUnlock,
                selectedWorkflowIds = selectedWorkflowIds,
                onToggleWorkflow = onToggleWorkflow,
                onToggleAllWorkflows = onToggleAllWorkflows,
                selectedCredentialIds = selectedCredentialIds,
                onToggleCredential = onToggleCredential,
                onToggleAllCredentials = onToggleAllCredentials,
                selectedPreconditionIds = selectedPreconditionIds,
                onTogglePrecondition = onTogglePrecondition,
                onToggleAllPreconditions = onToggleAllPreconditions,
                conflictStrategy = conflictStrategy,
                onSelectConflictStrategy = onSelectConflictStrategy,
            )
        }

        if (parseError != null) {
            ImportErrorBanner(errorMessage = parseError)
        }

        if (isImporting) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Restoring backup data...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ImportSourcePickers(
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Select how you would like to import your backup:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ImportSourceOptionCard(
            title = "Open JSON File",
            subtitle = "Select a saved .json backup file from device storage",
            icon = Icons.Default.FolderOpen,
            onClick = onPickFile,
        )

        ImportSourceOptionCard(
            title = "Paste from Clipboard",
            subtitle = "Restore directly from copied backup JSON text",
            icon = Icons.Default.ContentPaste,
            onClick = onPaste,
        )
    }
}

@Composable
private fun ImportSourceOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BackupBottomSheetHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    count: Int? = null,
    onCloseClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (count != null && count > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        thickness = 0.5.dp,
    )
}

private fun validateExportOptions(
    selectedWorkflowIds: Set<String>,
    autoIncludeDependencies: Boolean,
    includeStandaloneCredentials: Boolean,
    includeStandalonePreconditions: Boolean,
    hasDependencies: Boolean,
    encryptSecrets: Boolean,
    pinOrPassword: String,
    confirmPinOrPassword: String,
): Pair<ExportOptions?, String?> {
    val hasItems = selectedWorkflowIds.isNotEmpty() ||
        includeStandaloneCredentials ||
        includeStandalonePreconditions ||
        (autoIncludeDependencies && hasDependencies)
    val error =
        when {
            !hasItems ->
                "Select at least one workflow or item to export."
            encryptSecrets && pinOrPassword.length < 4 ->
                "PIN/Password must be at least 4 characters."
            encryptSecrets && pinOrPassword != confirmPinOrPassword ->
                "Passwords do not match."
            else -> null
        }
    val options =
        if (error == null) {
            ExportOptions(
                includeWorkflows = selectedWorkflowIds.isNotEmpty(),
                includeCredentials = includeStandaloneCredentials,
                includePreconditions = includeStandalonePreconditions,
                selectedWorkflowIds = selectedWorkflowIds,
                autoIncludeDependencies = autoIncludeDependencies,
                encryptSecrets = encryptSecrets,
                password = if (encryptSecrets) pinOrPassword else null,
            )
        } else {
            null
        }
    return Pair(options, error)
}

private fun saveExportJsonToUri(
    context: Context,
    uri: Uri,
    json: String,
    onSuccess: () -> Unit,
    onFailure: (String) -> Unit,
) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(json.toByteArray(Charsets.UTF_8))
        }
        onSuccess()
    } catch (e: IOException) {
        onFailure(e.message ?: "Failed to save file")
    } catch (e: SecurityException) {
        onFailure(e.message ?: "Permission denied")
    }
}

@Composable
private fun ExportPasswordCard(
    encryptSecrets: Boolean,
    onToggleEncrypt: (Boolean) -> Unit,
    pinOrPassword: String,
    onPasswordChange: (String) -> Unit,
    confirmPinOrPassword: String,
    onConfirmChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Encrypt Secrets with Password",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Switch(
                    checked = encryptSecrets,
                    onCheckedChange = onToggleEncrypt,
                    modifier = Modifier.size(width = 44.dp, height = 24.dp),
                )
            }

            if (encryptSecrets) {
                Text(
                    text = "Protects bot tokens, passwords, and API headers using AES-256-GCM. You will need this password when restoring on any device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = pinOrPassword,
                    onValueChange = onPasswordChange,
                    label = { Text("Backup PIN or Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = onToggleShowPassword) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPinOrPassword,
                    onValueChange = onConfirmChange,
                    label = { Text("Confirm PIN or Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (_: Exception) {
        null
    }

private fun readTextFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip
    return if (clip != null && clip.itemCount > 0) {
        clip.getItemAt(0).coerceToText(context)?.toString()
    } else {
        null
    }
}

@Composable
private fun ImportSummaryBadge(
    workflowsCount: Int,
    credentialsCount: Int,
    preconditionsCount: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ImportBadgeItem(icon = Icons.Default.AccountTree, count = workflowsCount, label = "Workflows")
            ImportBadgeItem(icon = Icons.Default.Key, count = credentialsCount, label = "Credentials")
            ImportBadgeItem(icon = Icons.Default.Shield, count = preconditionsCount, label = "Rules")
        }
    }
}

@Composable
private fun ImportBadgeItem(
    icon: ImageVector,
    count: Int,
    label: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text = "$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImportUnlockCard(
    unlockPassword: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    passwordError: String?,
    onUnlock: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text("Encrypted Backup", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Secrets in this backup are password-protected. Enter the PIN or password used during export to decrypt them:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = unlockPassword,
                onValueChange = onPasswordChange,
                label = { Text("Password or PIN") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = onToggleShowPassword) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                isError = passwordError != null,
                supportingText = if (passwordError != null) {
                    { Text(passwordError, color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onUnlock,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Unlock Secrets")
            }
        }
    }
}

@Composable
private fun ImportUnlockedNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("Secrets unlocked successfully", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ImportReviewContent(
    parsedData: ParsedBackupData,
    isUnlocked: Boolean,
    unlockPassword: String,
    onPasswordChange: (String) -> Unit,
    showUnlockPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    passwordError: String?,
    onUnlock: () -> Unit,
    selectedWorkflowIds: Set<String>,
    onToggleWorkflow: (String) -> Unit,
    onToggleAllWorkflows: (Boolean) -> Unit,
    selectedCredentialIds: Set<String>,
    onToggleCredential: (String) -> Unit,
    onToggleAllCredentials: (Boolean) -> Unit,
    selectedPreconditionIds: Set<String>,
    onTogglePrecondition: (String) -> Unit,
    onToggleAllPreconditions: (Boolean) -> Unit,
    conflictStrategy: ImportConflictStrategy,
    onSelectConflictStrategy: (ImportConflictStrategy) -> Unit,
) {
    val relatedDependencies = remember(selectedWorkflowIds, parsedData) {
        BackupDependencyResolver.resolveWorkflowDependencies(
            selectedWorkflowIds = selectedWorkflowIds,
            availableWorkflows = parsedData.workflows,
            availablePreconditions = parsedData.preconditions,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ImportSummaryBadge(
            workflowsCount = parsedData.workflows.size,
            credentialsCount = parsedData.credentials.size,
            preconditionsCount = parsedData.preconditions.size,
        )

        if (parsedData.isEncrypted && !isUnlocked) {
            ImportUnlockCard(
                unlockPassword = unlockPassword,
                onPasswordChange = onPasswordChange,
                showPassword = showUnlockPassword,
                onToggleShowPassword = onToggleShowPassword,
                passwordError = passwordError,
                onUnlock = onUnlock,
            )
        } else if (isUnlocked && unlockPassword.isNotBlank()) {
            ImportUnlockedNotice()
        }

        ImportCategorySection(
            title = "Workflows",
            icon = Icons.Default.AccountTree,
            totalCount = parsedData.workflows.size,
            selectedCount = selectedWorkflowIds.size,
            onToggleAll = onToggleAllWorkflows,
        ) {
            parsedData.workflows.forEach { wf ->
                ImportItemRow(
                    title = wf.name,
                    subtitle = "${wf.nodes.size} nodes",
                    checked = wf.id in selectedWorkflowIds,
                    onToggle = { onToggleWorkflow(wf.id) },
                )
            }
        }

        ImportCategorySection(
            title = "Credentials",
            icon = Icons.Default.Key,
            totalCount = parsedData.credentials.size,
            selectedCount = selectedCredentialIds.size,
            onToggleAll = onToggleAllCredentials,
        ) {
            parsedData.credentials.forEach { cred ->
                val isLinked = cred.id in relatedDependencies.credentialIds
                ImportItemRow(
                    title = cred.name,
                    subtitle = if (isLinked) "${cred.type} • Linked to workflow" else cred.type,
                    checked = cred.id in selectedCredentialIds,
                    onToggle = { onToggleCredential(cred.id) },
                )
            }
        }

        ImportCategorySection(
            title = "Precondition Rules",
            icon = Icons.Default.Shield,
            totalCount = parsedData.preconditions.size,
            selectedCount = selectedPreconditionIds.size,
            onToggleAll = onToggleAllPreconditions,
        ) {
            parsedData.preconditions.forEach { prec ->
                val isLinked = prec.id in relatedDependencies.preconditionIds
                ImportItemRow(
                    title = prec.name,
                    subtitle = if (isLinked) "${prec.type} • Linked to workflow" else prec.type,
                    checked = prec.id in selectedPreconditionIds,
                    onToggle = { onTogglePrecondition(prec.id) },
                )
            }
        }

        ImportConflictOptions(
            selectedStrategy = conflictStrategy,
            onSelectStrategy = onSelectConflictStrategy,
        )
    }
}

@Composable
private fun ImportCategorySection(
    title: String,
    icon: ImageVector,
    totalCount: Int,
    selectedCount: Int,
    onToggleAll: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    if (totalCount == 0) return

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = "$selectedCount/$totalCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                TextButton(
                    onClick = { onToggleAll(selectedCount < totalCount) },
                ) {
                    Text(
                        text = if (selectedCount == totalCount) "Deselect All" else "Select All",
                        fontSize = 12.sp,
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                thickness = 0.5.dp,
            )

            content()
        }
    }
}

@Composable
private fun ImportItemRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ImportConflictOptions(
    selectedStrategy: ImportConflictStrategy,
    onSelectStrategy: (ImportConflictStrategy) -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Conflict Resolution", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                text = "If an item with the same ID already exists:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            ImportConflictOptionRow(
                title = "Overwrite existing",
                subtitle = "Update existing workflows, credentials, or rules",
                selected = selectedStrategy == ImportConflictStrategy.OVERWRITE,
                onSelect = { onSelectStrategy(ImportConflictStrategy.OVERWRITE) },
            )

            ImportConflictOptionRow(
                title = "Keep both (Assign new IDs)",
                subtitle = "Import as new duplicates with fresh IDs",
                selected = selectedStrategy == ImportConflictStrategy.KEEP_BOTH,
                onSelect = { onSelectStrategy(ImportConflictStrategy.KEEP_BOTH) },
            )

            ImportConflictOptionRow(
                title = "Skip existing",
                subtitle = "Keep your current data and only import missing items",
                selected = selectedStrategy == ImportConflictStrategy.SKIP_EXISTING,
                onSelect = { onSelectStrategy(ImportConflictStrategy.SKIP_EXISTING) },
            )
        }
    }
}

@Composable
private fun ImportConflictOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onSelect() }
                .padding(vertical = 2.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ImportErrorBanner(errorMessage: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ImportBottomSheetBottomBar(
    hasParsedData: Boolean,
    totalSelected: Int,
    canImport: Boolean,
    onDismiss: () -> Unit,
    onChangeFile: () -> Unit,
    onImport: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasParsedData) {
            OutlinedButton(
                onClick = onChangeFile,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(44.dp),
            ) {
                Text("Change File")
            }

            Button(
                onClick = onImport,
                enabled = canImport,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import $totalSelected Items", fontWeight = FontWeight.SemiBold)
            }
        } else {
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp),
            ) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ImportCompleteBottomSheet(
    result: ImportResult,
    onDismiss: () -> Unit,
) {
    NoSwipeBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BackupBottomSheetHeader(
                title = "Import Complete",
                subtitle = "Data successfully restored into ZopHop",
                icon = Icons.Default.CheckCircle,
                onCloseClick = onDismiss,
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ImportResultRow(
                            icon = Icons.Default.AccountTree,
                            label = "Workflows imported",
                            count = result.importedWorkflowsCount,
                        )
                        ImportResultRow(
                            icon = Icons.Default.Key,
                            label = "Credentials imported",
                            count = result.importedCredentialsCount,
                        )
                        ImportResultRow(
                            icon = Icons.Default.Shield,
                            label = "Precondition rules imported",
                            count = result.importedPreconditionsCount,
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.5.dp,
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ImportResultRow(
    icon: ImageVector,
    label: String,
    count: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
