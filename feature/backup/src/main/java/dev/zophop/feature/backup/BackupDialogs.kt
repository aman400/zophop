package dev.zophop.feature.backup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.Workflow
import kotlinx.coroutines.launch
import java.io.IOException

@Composable
fun ExportBackupDialog(
    backupService: BackupService,
    onDismiss: () -> Unit,
    workflowsCount: Int? = null,
    credentialsCount: Int? = null,
    preconditionsCount: Int? = null,
    onExportFinished: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var counts by remember {
        mutableStateOf(
            BackupCounts(
                workflowsCount = workflowsCount ?: 0,
                credentialsCount = credentialsCount ?: 0,
                preconditionsCount = preconditionsCount ?: 0,
            ),
        )
    }

    LaunchedEffect(backupService) {
        if (workflowsCount == null || credentialsCount == null || preconditionsCount == null) {
            counts = backupService.getAvailableCounts()
        }
    }

    var includeWorkflows by remember { mutableStateOf(true) }
    var includeCredentials by remember { mutableStateOf(true) }
    var includePreconditions by remember { mutableStateOf(true) }

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
        val (options, validationError) = validateExportOptions(
            includeWorkflows = includeWorkflows,
            includeCredentials = includeCredentials,
            includePreconditions = includePreconditions,
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Upload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("Export Backup", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            ExportDialogBody(
                includeWorkflows = includeWorkflows,
                onToggleWorkflows = { includeWorkflows = it },
                workflowsCount = counts.workflowsCount,
                includeCredentials = includeCredentials,
                onToggleCredentials = { includeCredentials = it },
                credentialsCount = counts.credentialsCount,
                includePreconditions = includePreconditions,
                onTogglePreconditions = { includePreconditions = it },
                preconditionsCount = counts.preconditionsCount,
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
                errorMessage = errorMessage,
                isGenerating = isGenerating,
            )
        },
        confirmButton = {
            ExportDialogActionButtons(
                isGenerating = isGenerating,
                onCopy = {
                    handleExport { json ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("ZopHop Backup", json))
                        Toast.makeText(context, "Backup JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                        onExportFinished(json)
                        onDismiss()
                    }
                },
                onShare = {
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
                onSave = {
                    handleExport {
                        createDocumentLauncher.launch("zophop_backup_${System.currentTimeMillis()}.json")
                    }
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isGenerating) {
                Text("Cancel")
            }
        },
    )
}

private fun validateExportOptions(
    includeWorkflows: Boolean,
    includeCredentials: Boolean,
    includePreconditions: Boolean,
    encryptSecrets: Boolean,
    pinOrPassword: String,
    confirmPinOrPassword: String,
): Pair<ExportOptions?, String?> {
    val error =
        when {
            !includeWorkflows && !includeCredentials && !includePreconditions ->
                "Select at least one item type to export."
            encryptSecrets && pinOrPassword.length < 4 ->
                "PIN/Password must be at least 4 characters."
            encryptSecrets && pinOrPassword != confirmPinOrPassword ->
                "Passwords do not match."
            else -> null
        }
    val options =
        if (error == null) {
            ExportOptions(
                includeWorkflows = includeWorkflows,
                includeCredentials = includeCredentials,
                includePreconditions = includePreconditions,
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
private fun ExportDialogBody(
    includeWorkflows: Boolean,
    onToggleWorkflows: (Boolean) -> Unit,
    workflowsCount: Int,
    includeCredentials: Boolean,
    onToggleCredentials: (Boolean) -> Unit,
    credentialsCount: Int,
    includePreconditions: Boolean,
    onTogglePreconditions: (Boolean) -> Unit,
    preconditionsCount: Int,
    encryptSecrets: Boolean,
    onToggleEncrypt: (Boolean) -> Unit,
    pinOrPassword: String,
    onPasswordChange: (String) -> Unit,
    confirmPinOrPassword: String,
    onConfirmChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
    errorMessage: String?,
    isGenerating: Boolean,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Select data categories to include in this backup:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ExportCategoryCheckboxes(
            includeWorkflows = includeWorkflows,
            onToggleWorkflows = onToggleWorkflows,
            workflowsCount = workflowsCount,
            includeCredentials = includeCredentials,
            onToggleCredentials = onToggleCredentials,
            credentialsCount = credentialsCount,
            includePreconditions = includePreconditions,
            onTogglePreconditions = onTogglePreconditions,
            preconditionsCount = preconditionsCount,
        )

        ExportPasswordCard(
            encryptSecrets = encryptSecrets,
            onToggleEncrypt = onToggleEncrypt,
            pinOrPassword = pinOrPassword,
            onPasswordChange = onPasswordChange,
            confirmPinOrPassword = confirmPinOrPassword,
            onConfirmChange = onConfirmChange,
            showPassword = showPassword,
            onToggleShowPassword = onToggleShowPassword,
        )

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (isGenerating) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Generating backup...", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExportCategoryCheckboxes(
    includeWorkflows: Boolean,
    onToggleWorkflows: (Boolean) -> Unit,
    workflowsCount: Int,
    includeCredentials: Boolean,
    onToggleCredentials: (Boolean) -> Unit,
    credentialsCount: Int,
    includePreconditions: Boolean,
    onTogglePreconditions: (Boolean) -> Unit,
    preconditionsCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onToggleWorkflows(!includeWorkflows) },
        ) {
            Checkbox(checked = includeWorkflows, onCheckedChange = onToggleWorkflows)
            Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Workflows ($workflowsCount)", style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onToggleCredentials(!includeCredentials) },
        ) {
            Checkbox(checked = includeCredentials, onCheckedChange = onToggleCredentials)
            Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Credentials ($credentialsCount)", style = MaterialTheme.typography.bodyMedium)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onTogglePreconditions(!includePreconditions) },
        ) {
            Checkbox(checked = includePreconditions, onCheckedChange = onTogglePreconditions)
            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Preconditions ($preconditionsCount)", style = MaterialTheme.typography.bodyMedium)
        }
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
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = if (encryptSecrets) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Text(
                            text = "Encrypt Secrets with PIN/Password",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Protects API tokens, passwords, and sensitive headers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }
                Switch(checked = encryptSecrets, onCheckedChange = onToggleEncrypt)
            }

            if (encryptSecrets) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = pinOrPassword,
                    onValueChange = onPasswordChange,
                    label = { Text("PIN or Password") },
                    placeholder = { Text("Minimum 4 characters") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = onToggleShowPassword) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = confirmPinOrPassword,
                    onValueChange = onConfirmChange,
                    label = { Text("Confirm PIN or Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "Remember this PIN/password. Without it, exported secrets cannot be restored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportDialogActionButtons(
    isGenerating: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(onClick = onCopy, enabled = !isGenerating) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy")
        }

        OutlinedButton(onClick = onShare, enabled = !isGenerating) {
            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Share")
        }

        Button(onClick = onSave, enabled = !isGenerating) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Save to File")
        }
    }
}

@Composable
fun ImportBackupDialog(
    backupService: BackupService,
    onDismiss: () -> Unit,
    onImportFinished: (ImportResult) -> Unit = {},
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

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
                selectedWorkflowIds = parsed.workflows.map { it.id }.toSet()
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
                selectedWorkflowIds = decrypted.workflows.map { it.id }.toSet()
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
        ImportCompleteDialog(
            result = importResult!!,
            onDismiss = {
                onImportFinished(importResult!!)
                onDismiss()
            },
        )
        return
    }

    val totalSelected = selectedWorkflowIds.size + selectedCredentialIds.size + selectedPreconditionIds.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (parsedData == null) "Import Backup" else "Review & Import",
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (parsedData == null) {
                    ImportSourcePickers(
                        onPickFile = { openDocumentLauncher.launch(arrayOf("application/json", "text/*")) },
                        onPaste = { processImportJson(readTextFromClipboard(context)) },
                    )
                } else {
                    ImportReviewContent(
                        parsedData = parsedData!!,
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
                            selectedWorkflowIds = if (id in selectedWorkflowIds) selectedWorkflowIds - id else selectedWorkflowIds + id
                        },
                        selectedCredentialIds = selectedCredentialIds,
                        onToggleCredential = { id ->
                            selectedCredentialIds = if (id in selectedCredentialIds) selectedCredentialIds - id else selectedCredentialIds + id
                        },
                        selectedPreconditionIds = selectedPreconditionIds,
                        onTogglePrecondition = { id ->
                            selectedPreconditionIds = if (id in selectedPreconditionIds) selectedPreconditionIds - id else selectedPreconditionIds + id
                        },
                        conflictStrategy = conflictStrategy,
                        onSelectConflictStrategy = { conflictStrategy = it },
                    )
                }

                if (parseError != null) {
                    Text(parseError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (isImporting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importing items...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            ImportDialogConfirmButton(
                parsedData = parsedData,
                selectedCount = totalSelected,
                isUnlocked = isUnlocked,
                isImporting = isImporting,
                onImport = { handleExecuteImport() },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text("Cancel")
            }
        },
    )
}

private fun readTextFromUri(context: Context, uri: Uri): String? =
    try {
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private fun readTextFromClipboard(context: Context): String? =
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    } catch (_: SecurityException) {
        null
    }

@Composable
private fun ImportDialogConfirmButton(
    parsedData: ParsedBackupData?,
    selectedCount: Int,
    isUnlocked: Boolean,
    isImporting: Boolean,
    onImport: () -> Unit,
) {
    if (parsedData != null) {
        Button(
            onClick = onImport,
            enabled = !isImporting && selectedCount > 0 && (!parsedData.isEncrypted || isUnlocked),
        ) {
            Text("Import Selected ($selectedCount)")
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
    selectedCredentialIds: Set<String>,
    onToggleCredential: (String) -> Unit,
    selectedPreconditionIds: Set<String>,
    onTogglePrecondition: (String) -> Unit,
    conflictStrategy: ImportConflictStrategy,
    onSelectConflictStrategy: (ImportConflictStrategy) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        ImportItemChecklists(
            workflows = parsedData.workflows,
            selectedWorkflowIds = selectedWorkflowIds,
            onToggleWorkflow = onToggleWorkflow,
            credentials = parsedData.credentials,
            selectedCredentialIds = selectedCredentialIds,
            onToggleCredential = onToggleCredential,
            preconditions = parsedData.preconditions,
            selectedPreconditionIds = selectedPreconditionIds,
            onTogglePrecondition = onTogglePrecondition,
        )

        ImportConflictOptions(
            selectedStrategy = conflictStrategy,
            onSelectStrategy = onSelectConflictStrategy,
        )
    }
}

@Composable
private fun ImportCompleteDialog(
    result: ImportResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Import Complete")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Data successfully imported into ZopHop:", style = MaterialTheme.typography.bodyMedium)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("• ${result.importedWorkflowsCount} Workflows imported", fontWeight = FontWeight.SemiBold)
                        Text("• ${result.importedCredentialsCount} Credentials imported", fontWeight = FontWeight.SemiBold)
                        Text("• ${result.importedPreconditionsCount} Preconditions imported", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun ImportSourcePickers(
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Choose a ZopHop backup JSON file or paste directly from your clipboard:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Pick File")
            }
            OutlinedButton(onClick = onPaste, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Paste")
            }
        }
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
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(workflowsCount.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Workflows", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(credentialsCount.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text("Credentials", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(preconditionsCount.toString(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                Text("Rules", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(text = "Secrets are Encrypted", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "Enter the PIN/password used when creating this backup to decrypt tokens and credentials:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            OutlinedTextField(
                value = unlockPassword,
                onValueChange = onPasswordChange,
                label = { Text("PIN or Password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = onToggleShowPassword) {
                        Icon(imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (passwordError != null) {
                Text(passwordError, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
            Button(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                Text("Unlock Secrets")
            }
        }
    }
}

@Composable
private fun ImportUnlockedNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text("Secrets unlocked successfully", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ImportItemChecklists(
    workflows: List<Workflow>,
    selectedWorkflowIds: Set<String>,
    onToggleWorkflow: (String) -> Unit,
    credentials: List<Credential>,
    selectedCredentialIds: Set<String>,
    onToggleCredential: (String) -> Unit,
    preconditions: List<PreconditionEntity>,
    selectedPreconditionIds: Set<String>,
    onTogglePrecondition: (String) -> Unit,
) {
    if (workflows.isNotEmpty()) {
        Text("Workflows:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        workflows.forEach { wf ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleWorkflow(wf.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = wf.id in selectedWorkflowIds, onCheckedChange = { onToggleWorkflow(wf.id) })
                Text(wf.name, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (credentials.isNotEmpty()) {
        Text("Credentials:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        credentials.forEach { cred ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggleCredential(cred.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = cred.id in selectedCredentialIds, onCheckedChange = { onToggleCredential(cred.id) })
                Text("${cred.name} (${cred.type})", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    if (preconditions.isNotEmpty()) {
        Text("Preconditions:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        preconditions.forEach { prec ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onTogglePrecondition(prec.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = prec.id in selectedPreconditionIds, onCheckedChange = { onTogglePrecondition(prec.id) })
                Text("${prec.name} (${prec.type})", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ImportConflictOptions(
    selectedStrategy: ImportConflictStrategy,
    onSelectStrategy: (ImportConflictStrategy) -> Unit,
) {
    Text("If an item already exists:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onSelectStrategy(ImportConflictStrategy.OVERWRITE) },
        ) {
            RadioButton(
                selected = selectedStrategy == ImportConflictStrategy.OVERWRITE,
                onClick = { onSelectStrategy(ImportConflictStrategy.OVERWRITE) },
            )
            Text("Overwrite existing", style = MaterialTheme.typography.bodySmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onSelectStrategy(ImportConflictStrategy.KEEP_BOTH) },
        ) {
            RadioButton(
                selected = selectedStrategy == ImportConflictStrategy.KEEP_BOTH,
                onClick = { onSelectStrategy(ImportConflictStrategy.KEEP_BOTH) },
            )
            Text("Keep both (assign new IDs)", style = MaterialTheme.typography.bodySmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onSelectStrategy(ImportConflictStrategy.SKIP_EXISTING) },
        ) {
            RadioButton(
                selected = selectedStrategy == ImportConflictStrategy.SKIP_EXISTING,
                onClick = { onSelectStrategy(ImportConflictStrategy.SKIP_EXISTING) },
            )
            Text("Skip existing", style = MaterialTheme.typography.bodySmall)
        }
    }
}
