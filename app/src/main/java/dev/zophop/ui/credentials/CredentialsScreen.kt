package dev.zophop.ui.credentials

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.zophop.core.ui.components.BiometricAuthHelper
import dev.zophop.core.ui.components.CredentialBottomSheet
import dev.zophop.domain.engine.plugin.CredentialSpecRegistry
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.ui.MainViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CredentialsScreen(
    viewModel: MainViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val credentials by viewModel.credentials.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val preconditions by viewModel.preconditions.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pluginRefreshTick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    pluginRefreshTick++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var availableCredentialTypeIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(pluginRefreshTick) {
        val specs = CredentialSpecRegistry(context).getAllCredentialSpecs()
        availableCredentialTypeIds = specs.map { it.typeId }.toSet()
    }
    var editingCredential by remember { mutableStateOf<Credential?>(null) }
    var pendingDeleteCredential by remember { mutableStateOf<Credential?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun requestEnableSecurity() {
        if (BiometricAuthHelper.canBypassWhenNoLockEnrolled(context)) {
            viewModel.toggleLockCredentialsWithBiometric(true)
            return
        }
        val authenticators = BiometricAuthHelper.allowedAuthenticators()
        val biometricManager = BiometricManager.from(context)
        val activity = context as? FragmentActivity
        if (activity != null && biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt =
                BiometricPrompt(
                    activity,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            viewModel.toggleLockCredentialsWithBiometric(true)
                        }
                    },
                )
            val promptInfo =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle("Enable Vault Security")
                    .setSubtitle("Authenticate using biometrics or device PIN/pattern to enable vault security lock.")
                    .setAllowedAuthenticators(authenticators)
                    .build()

            prompt.authenticate(promptInfo)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(modifier = Modifier.height(12.dp)) }

            // Biometric Security Lock Suggestion Banner
            if (!uiState.lockCredentialsWithBiometric && !uiState.dismissedBiometricBanner) {
                item {
                    BiometricSecuritySuggestionBanner(
                        onEnableClick = { requestEnableSecurity() },
                        onDismissClick = { viewModel.dismissBiometricBanner() },
                    )
                }
            }

            if (credentials.isEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            CardDefaults.outlinedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No saved credentials found. Tap '+ Add Credential' below to add one.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(credentials, key = { it.id }) { cred ->
                    val linkedWorkflows =
                        remember(workflows, cred) {
                            workflows.filter { wf ->
                                wf.nodes.any { node ->
                                    try {
                                        parseJsonObjectSafe(node.configJson).optString("credentialId") == cred.id
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                            }
                        }
                    val linkedPreconditions =
                        remember(preconditions, cred) {
                            preconditions.filter { prec -> prec.credentialId == cred.id }
                        }

                    val isMissingPlugin =
                        remember(cred.type, availableCredentialTypeIds) {
                            CredentialSpecRegistry.isCredentialTypeMissingPlugin(
                                credentialType = cred.type,
                                availableCredentialTypeIds = availableCredentialTypeIds,
                            )
                        }

                    CredentialCardItem(
                        credential = cred,
                        linkedWorkflowCount = linkedWorkflows.size,
                        linkedPreconditionCount = linkedPreconditions.size,
                        isMissingPlugin = isMissingPlugin,
                        onEdit = { editingCredential = cred },
                        onDelete = { pendingDeleteCredential = cred },
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }

        ExtendedFloatingActionButton(
            onClick = { showAddDialog = true },
            icon = { Icon(Icons.Default.Add, contentDescription = "Add Credential") },
            text = { Text("Add Credential") },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }

    if (showAddDialog) {
        CredentialBottomSheet(
            credential = null,
            linkedWorkflowCount = 0,
            linkedPreconditionCount = 0,
            isLockEnabled = uiState.lockCredentialsWithBiometric,
            onDismiss = { showAddDialog = false },
            onSave = { newCred ->
                viewModel.saveCredential(newCred)
                showAddDialog = false
            },
        )
    }

    editingCredential?.let { cred ->
        val linkedWorkflows =
            remember(workflows, cred) {
                workflows.filter { wf ->
                    wf.nodes.any { node ->
                        try {
                            parseJsonObjectSafe(node.configJson).optString("credentialId") == cred.id
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }
        val linkedPreconditions =
            remember(preconditions, cred) {
                preconditions.filter { prec -> prec.credentialId == cred.id }
            }

        CredentialBottomSheet(
            credential = cred,
            linkedWorkflowCount = linkedWorkflows.size,
            linkedPreconditionCount = linkedPreconditions.size,
            isLockEnabled = uiState.lockCredentialsWithBiometric,
            onDismiss = { editingCredential = null },
            onSave = { updated ->
                viewModel.saveCredential(updated)
                editingCredential = null
            },
        )
    }

    pendingDeleteCredential?.let { cred ->
        val linkedWorkflows =
            remember(workflows, cred) {
                workflows.filter { wf ->
                    wf.nodes.any { node ->
                        try {
                            parseJsonObjectSafe(node.configJson).optString("credentialId") == cred.id
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }
        val linkedPreconditions =
            remember(preconditions, cred) {
                preconditions.filter { prec -> prec.credentialId == cred.id }
            }

        if (linkedWorkflows.isNotEmpty() || linkedPreconditions.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { pendingDeleteCredential = null },
                title = { Text("Cannot Delete Credential") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Credential '${cred.name}' is currently in use:")
                        if (linkedWorkflows.isNotEmpty()) {
                            Text("Workflows (${linkedWorkflows.size}):", fontWeight = FontWeight.Bold)
                            linkedWorkflows.forEach { wf ->
                                Text("• ${wf.name}")
                            }
                        }
                        if (linkedPreconditions.isNotEmpty()) {
                            Text("Preconditions (${linkedPreconditions.size}):", fontWeight = FontWeight.Bold)
                            linkedPreconditions.forEach { prec ->
                                Text("• ${prec.name}")
                            }
                        }
                        Text(
                            "Please remove this credential from the items above before deleting it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { pendingDeleteCredential = null }) {
                        Text("OK")
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { pendingDeleteCredential = null },
                title = { Text("Delete Credential?") },
                text = { Text("Are you sure you want to delete credential '${cred.name}'?") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteCredential(cred.id)
                            pendingDeleteCredential = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteCredential = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
fun BiometricSecuritySuggestionBanner(
    onEnableClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
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
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Enhance Vault Security",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(
                    onClick = onDismissClick,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss Suggestion",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Text(
                text = "Require Biometrics or Device Credentials (PIN, Pattern, Password) to reveal secret passwords & tokens in your Vault.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onEnableClick,
                    modifier = Modifier.heightIn(min = 34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Enable Biometric Lock", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CredentialCardItem(
    credential: Credential,
    linkedWorkflowCount: Int = 0,
    linkedPreconditionCount: Int = 0,
    isMissingPlugin: Boolean = false,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentOnDelete by rememberUpdatedState(onDelete)

    LaunchedEffect(dismissState.settledValue) {
        when (dismissState.settledValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                currentOnDelete()
                dismissState.reset()
            }
            SwipeToDismissBoxValue.StartToEnd -> {
                currentOnEdit()
                dismissState.reset()
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color =
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.secondaryContainer
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                }
            val alignment = if (direction == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            val icon = if (direction == SwipeToDismissBoxValue.StartToEnd) Icons.Default.Edit else Icons.Default.Delete
            val text = if (direction == SwipeToDismissBoxValue.StartToEnd) "Edit" else "Delete"
            val tint =
                if (direction ==
                    SwipeToDismissBoxValue.StartToEnd
                ) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                }

            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(color, shape = RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(imageVector = icon, contentDescription = text, tint = tint, modifier = Modifier.size(16.dp))
                    Text(text = text, color = tint, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        },
    ) {
        OutlinedCard(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit),
            shape = RoundedCornerShape(14.dp),
            colors =
                CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Column {
                            Text(
                                text = credential.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = credential.type.replace("_", " "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                if (isMissingPlugin) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = "Plugin not installed (${credential.type})",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // Usage Badges
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isMissingPlugin) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(11.dp))
                                Text(
                                    text = "Missing Plugin",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Surface(
                        color =
                            if (linkedWorkflowCount >
                                0
                            ) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        contentColor =
                            if (linkedWorkflowCount >
                                0
                            ) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(11.dp))
                            Text(
                                text =
                                    if (linkedWorkflowCount >
                                        0
                                    ) {
                                        "$linkedWorkflowCount Workflow${if (linkedWorkflowCount > 1) "s" else ""}"
                                    } else {
                                        "No Workflows"
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Surface(
                        color =
                            if (linkedPreconditionCount >
                                0
                            ) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        contentColor =
                            if (linkedPreconditionCount >
                                0
                            ) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(11.dp))
                            Text(
                                text =
                                    if (linkedPreconditionCount >
                                        0
                                    ) {
                                        "$linkedPreconditionCount Precondition${if (linkedPreconditionCount > 1) "s" else ""}"
                                    } else {
                                        "No Preconditions"
                                    },
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
