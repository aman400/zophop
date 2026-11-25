package dev.zophop.ui

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.zophop.data.AppDatabase
import dev.zophop.data.AppSettings
import dev.zophop.data.CredentialRepository
import dev.zophop.data.DefaultWorkflowInitializer
import dev.zophop.data.DeliveryNode
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.MessageEntity
import dev.zophop.data.PreconditionEntity
import dev.zophop.data.PreconditionRepository
import dev.zophop.data.RoomCredentialRepository
import dev.zophop.data.RoomPreconditionRepository
import dev.zophop.data.RoomWorkflowRepository
import dev.zophop.data.WorkflowExecutionLogEntity
import dev.zophop.data.WorkflowRepository
import dev.zophop.data.toDomain
import dev.zophop.domain.engine.ActiveExecutionTracker
import dev.zophop.domain.engine.ActiveWorkflowExecution
import dev.zophop.domain.engine.ExecutionJobRegistry
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.NodeStepState
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.WorkflowExecutionResult
import dev.zophop.domain.engine.toJsonString
import dev.zophop.domain.engine.toNodeExecutionSteps
import dev.zophop.domain.model.Credential
import dev.zophop.domain.model.NodeType
import dev.zophop.domain.model.Workflow
import dev.zophop.domain.model.WorkflowNode
import dev.zophop.domain.model.optString
import dev.zophop.domain.model.parseJsonObjectSafe
import dev.zophop.filter.SmsFilter
import dev.zophop.plugin.api.ExecutionContext
import dev.zophop.service.SmsForwarderService
import dev.zophop.worker.ForwardingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.UUID

typealias ActiveExecutionState = ActiveWorkflowExecution

data class MainUiState(
    val nodes: List<DeliveryNode> = emptyList(),
    val allowlist: String = ".*",
    val keywordRegex: String = ".*",
    val senderRegex: String = ".*",
    val bodyParseRegex: String = "\\b\\d{4,8}\\b",
    val customMessageTemplate: String = "\${body}",
    val expiryMinutes: String = "10",
    val isEnabled: Boolean = false,
    val allowInsecureHttp: Boolean = false,
    val forwardNotifications: Boolean = false,
    val showWorkflowNotifications: Boolean = true,
    val maxParallelExecutions: Int = 3,
    val maxExecutionQueueSize: Int = 20,
    val autoClearEnabled: Boolean = false,
    val autoClearMinutes: String = "1440",
    val lockCredentialsWithBiometric: Boolean = false,
    val globalWorkflowsEnabled: Boolean = true,
    val dismissedBiometricBanner: Boolean = false,
    val isRegexValid: Boolean = true,
    val isUrlValid: Boolean = true,
    val isSendingTest: Boolean = false,
    val retryingMessageId: String? = null,
    val testResultMessage: String? = null,
    val isBatteryOptimized: Boolean = true,
    val hasNotificationListenerPermission: Boolean = false,
    val selectedWorkflowForLogs: Workflow? = null,
) {
    val activeNodes: List<DeliveryNode>
        get() = nodes.filter { it.isEnabled && it.serverUrl.isNotBlank() }
}

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val settingsRepo = EncryptedSettingsRepository(application)
    private val db = AppDatabase.getInstance(application)
    private val forwardingManager = ForwardingManager.getInstance(application)
    val workflowRepository: WorkflowRepository = RoomWorkflowRepository(db.workflowDao(), db.preconditionDao())
    val credentialRepository: CredentialRepository = RoomCredentialRepository(db.credentialDao())
    val preconditionRepository: PreconditionRepository = RoomPreconditionRepository(db.preconditionDao())
    private val workflowInitializer = DefaultWorkflowInitializer(workflowRepository)

    private val externalPluginRegistry =
        dev.zophop.domain.engine.plugin
            .ExternalPluginRegistry(application)
    private val _discoveredPlugins = MutableStateFlow<List<dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo>>(emptyList())
    val discoveredPlugins: StateFlow<List<dev.zophop.domain.engine.plugin.ExternalPluginServiceInfo>> = _discoveredPlugins.asStateFlow()

    init {
        discoverInstalledPlugins()
    }

    fun discoverInstalledPlugins() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val plugins = externalPluginRegistry.discoverPlugins()
                _discoveredPlugins.value = plugins
            } catch (_: Exception) {
            }
        }
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _activeExecutionState = MutableStateFlow<ActiveExecutionState?>(null)
    val activeExecutionState: StateFlow<ActiveExecutionState?> = _activeExecutionState.asStateFlow()

    private val _targetExecutingWorkflowId = MutableStateFlow<String?>(null)
    val targetExecutingWorkflowId: StateFlow<String?> = _targetExecutingWorkflowId.asStateFlow()

    fun clearTargetExecutingWorkflowId() {
        _targetExecutingWorkflowId.value = null
    }

    fun navigateToExecutingWorkflow(workflowId: String) {
        val execution = ActiveExecutionTracker.getExecution(workflowId)
        if (execution != null) {
            _activeExecutionState.value = execution
            _targetExecutingWorkflowId.value = workflowId
        } else {
            openWorkflowLogsById(workflowId)
        }
    }

    val workflows: StateFlow<List<Workflow>> =
        kotlinx.coroutines.flow
            .combine(
                workflowRepository.getAllWorkflowsFlow(),
                preconditionRepository.getAllPreconditionsFlow(),
            ) { workflowList, preconditionEntities ->
                val precMap = preconditionEntities.associateBy { it.id }
                workflowList.map { workflow ->
                    workflow.copy(
                        nodes =
                            workflow.nodes.map { node ->
                                if (node.preconditions.isEmpty()) {
                                    node
                                } else {
                                    node.copy(
                                        preconditions =
                                            node.preconditions.map { prec ->
                                                val latestEntity = precMap[prec.id] ?: precMap.values.find { it.type == prec.type.name }
                                                latestEntity?.toDomain() ?: prec
                                            },
                                    )
                                }
                            },
                    )
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val credentials: StateFlow<List<Credential>> =
        credentialRepository
            .getAllCredentialsFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val preconditions: StateFlow<List<PreconditionEntity>> =
        preconditionRepository
            .getAllPreconditionsFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val executionLogs: StateFlow<List<WorkflowExecutionLogEntity>> =
        workflowRepository
            .getRecentExecutionLogsFlow(50)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val recentEvents: StateFlow<List<MessageEntity>> =
        db
            .messageDao()
            .getRecentEventsFlow(20)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val allEvents: StateFlow<List<MessageEntity>> =
        db
            .messageDao()
            .getAllEventsFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val queuedMessages: StateFlow<List<MessageEntity>> =
        db
            .messageDao()
            .getQueuedMessagesFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    val queuedWorkflowLogs: StateFlow<List<WorkflowExecutionLogEntity>> =
        workflowRepository
            .getQueuedWorkflowLogsFlow()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList(),
            )

    fun processQueueNow() {
        viewModelScope.launch(Dispatchers.IO) {
            forwardingManager.processPendingQueue()
            dev.zophop.worker.WorkflowQueueManager.processQueuedWorkflows(getApplication())
        }
    }

    fun retryQueuedWorkflow(log: WorkflowExecutionLogEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val workflow = workflowRepository.getWorkflowById(log.workflowId) ?: return@launch
            val steps =
                try {
                    log.logs.toNodeExecutionSteps()
                } catch (_: Exception) {
                    null
                }
            val failedNodeId = steps?.lastOrNull {
                it.state == NodeStepState.FAILED || it.nodeId.startsWith("queue")
            }?.nodeId
            retryWorkflowFromFailedNode(
                workflow = workflow,
                failedNodeId = failedNodeId,
                originalLogId = log.id,
                onProgressUpdate = {},
                onResult = {},
            )
        }
    }

    fun deleteQueuedWorkflowLog(logId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workflowRepository.deleteExecutionLogById(logId)
        }
    }

    fun deleteQueuedMessage(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.messageDao().deleteMessage(id)
        }
    }

    init {
        loadSettings()
        checkPermissionsAndStatus()
        viewModelScope.launch(Dispatchers.IO) {
            workflowInitializer.checkAndInitialize()
        }
        viewModelScope.launch {
            ActiveExecutionTracker.executions.collect { executions ->
                val active = executions.values.firstOrNull { it.isExecuting }
                if (active != null) {
                    _activeExecutionState.value = active
                } else {
                    val currentId = _activeExecutionState.value?.workflowId
                    if (currentId != null && executions.containsKey(currentId)) {
                        _activeExecutionState.value = executions[currentId]
                    }
                }
            }
        }
    }

    fun loadSettings() {
        val s = settingsRepo.getSettings()
        _uiState.value =
            _uiState.value.copy(
                nodes = s.nodes,
                allowlist = s.allowlist,
                keywordRegex = s.keywordRegex,
                senderRegex = s.senderRegex,
                bodyParseRegex = s.bodyParseRegex,
                customMessageTemplate = s.customMessageTemplate,
                expiryMinutes = s.expiryMinutes.toString(),
                isEnabled = s.isEnabled,
                allowInsecureHttp = s.allowInsecureHttp,
                forwardNotifications = s.forwardNotifications,
                showWorkflowNotifications = s.showWorkflowNotifications,
                maxParallelExecutions = s.maxParallelExecutions,
                maxExecutionQueueSize = s.maxExecutionQueueSize,
                autoClearEnabled = s.autoClearEnabled,
                autoClearMinutes = s.autoClearMinutes.toString(),
                lockCredentialsWithBiometric = s.lockCredentialsWithBiometric,
                globalWorkflowsEnabled = s.globalWorkflowsEnabled,
                dismissedBiometricBanner = s.dismissedBiometricBanner,
                isRegexValid = SmsFilter.isValidRegex(s.keywordRegex),
                isUrlValid = true,
                isBatteryOptimized = checkBatteryOptimizedInternal(),
                hasNotificationListenerPermission = checkNotificationListenerPermissionInternal(),
            )
    }

    fun addNode(node: DeliveryNode) {
        val updated = _uiState.value.nodes + node
        _uiState.value = _uiState.value.copy(nodes = updated)
        saveCurrentSettings()
    }

    fun updateNode(node: DeliveryNode) {
        val updated = _uiState.value.nodes.map { if (it.id == node.id) node else it }
        _uiState.value = _uiState.value.copy(nodes = updated)
        saveCurrentSettings()
    }

    fun deleteNode(nodeId: String) {
        val updated = _uiState.value.nodes.filterNot { it.id == nodeId }
        _uiState.value = _uiState.value.copy(nodes = updated)
        saveCurrentSettings()
    }

    fun openWorkflowLogsById(workflowId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val wf = workflowRepository.getWorkflowById(workflowId)
            if (wf != null) {
                _uiState.value = _uiState.value.copy(selectedWorkflowForLogs = wf)
            }
        }
    }

    fun clearSelectedWorkflowForLogs() {
        _uiState.value = _uiState.value.copy(selectedWorkflowForLogs = null)
    }

    fun toggleNode(
        nodeId: String,
        enabled: Boolean,
    ) {
        val updated = _uiState.value.nodes.map { if (it.id == nodeId) it.copy(isEnabled = enabled) else it }
        _uiState.value = _uiState.value.copy(nodes = updated)
        saveCurrentSettings()
    }

    fun updateAllowlist(allowlist: String) {
        _uiState.value = _uiState.value.copy(allowlist = allowlist)
        saveCurrentSettings()
    }

    fun updateKeywordRegex(regex: String) {
        val isValid = SmsFilter.isValidRegex(regex)
        _uiState.value =
            _uiState.value.copy(
                keywordRegex = regex,
                isRegexValid = isValid,
            )
        if (isValid) {
            saveCurrentSettings()
        }
    }

    fun updateSenderRegex(regex: String) {
        _uiState.value = _uiState.value.copy(senderRegex = regex)
        saveCurrentSettings()
    }

    fun updateBodyParseRegex(regex: String) {
        _uiState.value = _uiState.value.copy(bodyParseRegex = regex)
        saveCurrentSettings()
    }

    fun updateCustomMessageTemplate(template: String) {
        _uiState.value = _uiState.value.copy(customMessageTemplate = template)
        saveCurrentSettings()
    }

    fun updateExpiryMinutes(expiry: String) {
        _uiState.value = _uiState.value.copy(expiryMinutes = expiry)
        saveCurrentSettings()
    }

    fun toggleAutoClear(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoClearEnabled = enabled)
        saveCurrentSettings()
    }

    fun updateAutoClearMinutes(minutes: String) {
        _uiState.value = _uiState.value.copy(autoClearMinutes = minutes)
        saveCurrentSettings()
    }

    fun toggleAllowInsecureHttp(allow: Boolean) {
        _uiState.value = _uiState.value.copy(allowInsecureHttp = allow)
        saveCurrentSettings()
    }

    fun toggleForwardNotifications(enable: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                forwardNotifications = enable,
                hasNotificationListenerPermission = checkNotificationListenerPermissionInternal(),
            )
        saveCurrentSettings()
    }

    fun toggleShowWorkflowNotifications(enable: Boolean) {
        _uiState.value = _uiState.value.copy(showWorkflowNotifications = enable)
        saveCurrentSettings()
    }

    fun toggleLockCredentialsWithBiometric(enable: Boolean) {
        _uiState.value = _uiState.value.copy(lockCredentialsWithBiometric = enable)
        saveCurrentSettings()
    }

    fun toggleGlobalWorkflowsEnabled(enable: Boolean) {
        _uiState.value = _uiState.value.copy(globalWorkflowsEnabled = enable)
        saveCurrentSettings()
    }

    fun dismissBiometricBanner() {
        _uiState.value = _uiState.value.copy(dismissedBiometricBanner = true)
        saveCurrentSettings()
    }

    fun updateMaxParallelExecutions(count: Int) {
        _uiState.value = _uiState.value.copy(maxParallelExecutions = count.coerceIn(1, 10))
        saveCurrentSettings()
    }

    fun updateMaxExecutionQueueSize(size: Int) {
        _uiState.value = _uiState.value.copy(maxExecutionQueueSize = size.coerceIn(5, 100))
        saveCurrentSettings()
    }

    fun toggleMasterSwitch(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isEnabled = enabled)
        saveCurrentSettings()
        val context = getApplication<Application>()
        if (enabled) {
            SmsForwarderService.startService(context)
        } else {
            SmsForwarderService.stopService(context)
        }
    }

    fun sendTestMessage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingTest = true, testResultMessage = null)
            val result = forwardingManager.sendTestMessage()
            _uiState.value =
                _uiState.value.copy(
                    isSendingTest = false,
                    testResultMessage = if (result.isSuccess) "Test ping sent to active delivery nodes!" else "Failed: ${result.exceptionOrNull()?.message}",
                )
        }
    }

    fun retryMessage(
        messageId: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(retryingMessageId = messageId)
            val result = forwardingManager.retryMessage(messageId)
            _uiState.value = _uiState.value.copy(retryingMessageId = null)
            if (result.isSuccess) {
                onResult(true, "Message retried successfully!")
            } else {
                onResult(false, result.exceptionOrNull()?.message ?: "Retry failed")
            }
        }
    }

    fun clearAllEvents() {
        viewModelScope.launch {
            forwardingManager.clearAllEvents()
        }
    }

    fun saveWorkflow(workflow: Workflow) {
        viewModelScope.launch(Dispatchers.IO) {
            workflowRepository.saveWorkflow(workflow)
        }
    }

    fun setWorkflowEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val workflow = workflowRepository.getWorkflowById(id)
            if (workflow != null && enabled) {
                val hasNotifTrigger = workflow.nodes.any { it.type == NodeType.NOTIFICATION_TRIGGER }
                val hasNotifPerm = checkNotificationListenerPermissionInternal()
                if (hasNotifTrigger && !hasNotifPerm) {
                    workflowRepository.setWorkflowEnabled(id, false)
                    return@launch
                }
            }
            workflowRepository.setWorkflowEnabled(id, enabled)
        }
    }

    fun deleteWorkflow(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workflowRepository.deleteWorkflow(id)
        }
    }

    fun getLogsForWorkflow(workflowId: String): Flow<List<WorkflowExecutionLogEntity>> =
        workflowRepository.getLogsForWorkflowFlow(workflowId)

    fun clearLogsForWorkflow(workflowId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            workflowRepository.clearLogsForWorkflow(workflowId)
        }
    }

    fun cancelWorkflowExecution(workflowId: String) {
        ExecutionJobRegistry.cancelJob(workflowId)
    }

    fun clearActiveExecutionState() {
        val currentWfId = _activeExecutionState.value?.workflowId
        if (currentWfId != null) {
            ActiveExecutionTracker.removeExecution(currentWfId)
        }
        _activeExecutionState.value = null
    }

    fun saveCredential(credential: Credential) {
        viewModelScope.launch(Dispatchers.IO) {
            credentialRepository.saveCredential(credential)
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            credentialRepository.deleteCredential(id)
        }
    }

    fun savePrecondition(precondition: PreconditionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            preconditionRepository.savePrecondition(precondition)
        }
    }

    fun deletePrecondition(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            preconditionRepository.deletePrecondition(id)
        }
    }

    fun testPreconditionWithProgress(
        entity: PreconditionEntity,
        onStepUpdate: (List<NodeExecutionStep>) -> Unit,
        onResult: (PreconditionResult) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val evaluator = PreconditionEvaluator(context)
            val domainPrecondition = entity.toDomain()
            val result =
                evaluator.evaluate(domainPrecondition) { steps ->
                    viewModelScope.launch(Dispatchers.Main) {
                        onStepUpdate(steps)
                    }
                }
            withContext(Dispatchers.Main) {
                onResult(result)
            }
        }
    }

    fun testPrecondition(
        entity: PreconditionEntity,
        onResult: (PreconditionResult) -> Unit,
    ) {
        testPreconditionWithProgress(entity, {}, onResult)
    }

    fun testRunWorkflowWithProgress(
        workflow: Workflow,
        onProgressUpdate: (List<NodeExecutionStep>) -> Unit,
        onResult: (WorkflowExecutionResult) -> Unit,
    ): kotlinx.coroutines.Job = retryWorkflowFromFailedNode(workflow, null, null, onProgressUpdate, onResult)

    fun retryWorkflowFromFailedNode(
        workflow: Workflow,
        failedNodeId: String?,
        originalLogId: String? = null,
        onProgressUpdate: (List<NodeExecutionStep>) -> Unit,
        onResult: (WorkflowExecutionResult) -> Unit,
    ): kotlinx.coroutines.Job {
        val job =
            viewModelScope.launch(Dispatchers.IO) {
                val newExecutionId = UUID.randomUUID().toString()
                if (originalLogId != null && failedNodeId != null) {
                    markNodeAsRetriedInOriginalLog(originalLogId, failedNodeId, newExecutionId)
                }

                _activeExecutionState.value =
                    ActiveExecutionState(
                        workflowId = workflow.id,
                        workflowName = workflow.name,
                        steps = emptyList(),
                        isExecuting = true,
                    )

                val initialLog =
                    WorkflowExecutionLogEntity(
                        id = newExecutionId,
                        workflowId = workflow.id,
                        status = "RUNNING",
                        timestamp = System.currentTimeMillis(),
                        messageId = null,
                        logs = emptyList<NodeExecutionStep>().toJsonString(),
                        errorReason = null,
                    )
                workflowRepository.saveExecutionLog(initialLog)

                val context = getApplication<Application>()
                val evaluator = PreconditionEvaluator(context)
                val engine = WorkflowEngine(evaluator, credentialRepository)

                val triggerNode =
                    workflow.nodes.firstOrNull { node ->
                        val manifest =
                            dev.zophop.domain.engine.plugin.BuiltInPluginRegistry
                                .getBuiltInManifestByTypeId(node.type)
                        manifest?.category == dev.zophop.plugin.api.NodeCategory.TRIGGER ||
                            node.type.contains("TRIGGER", ignoreCase = true) ||
                            workflow.connections.none { it.toNodeId == node.id }
                    }
                val triggerConfig = parseJsonObjectSafe(triggerNode?.configJson ?: "{}")

                val testContextMap = mutableMapOf<String, Any>()
                for ((key, value) in triggerConfig) {
                    if (key.startsWith("test", ignoreCase = true) && key.length > 4) {
                        val contextKey = key.substring(4).replaceFirstChar { it.lowercase() }
                        val valStr = value.optString()
                        if (valStr.isNotBlank()) {
                            testContextMap[contextKey] = valStr
                            if (contextKey == "body" || contextKey == "text") {
                                testContextMap["body"] = valStr
                                testContextMap["text"] = valStr
                                testContextMap["message"] = valStr
                                testContextMap["http_response"] = valStr
                            }
                            if (contextKey == "sender") {
                                testContextMap["sender"] = valStr
                                testContextMap["header"] = valStr
                                testContextMap["address"] = valStr
                            }
                            if (contextKey == "packageName") {
                                testContextMap["package_name"] = valStr
                                testContextMap["package"] = valStr
                            }
                        }
                    }
                }

                populateTriggerTestContext(triggerNode, triggerConfig, testContextMap)

                val execContext = ExecutionContext(testContextMap)

                val result =
                    engine.executeWorkflowWithProgress(
                        workflow = workflow,
                        context = execContext,
                        appContext = context,
                        startNodeId = failedNodeId,
                        onStepUpdate = { steps ->
                            _activeExecutionState.value =
                                ActiveExecutionState(
                                    workflowId = workflow.id,
                                    workflowName = workflow.name,
                                    steps = steps,
                                    isExecuting = true,
                                )
                            viewModelScope.launch(Dispatchers.Main) {
                                onProgressUpdate(steps)
                            }
                            if (steps.any { it.nodeId.startsWith("queue") }) {
                                val waitingStep = steps.lastOrNull { it.nodeId.startsWith("queue") }
                                val queuedLog =
                                    WorkflowExecutionLogEntity(
                                        id = newExecutionId,
                                        workflowId = workflow.id,
                                        status = "QUEUED",
                                        timestamp = System.currentTimeMillis(),
                                        messageId = null,
                                        logs = steps.toJsonString(),
                                        errorReason = waitingStep?.logMessage ?: "Waiting for preconditions in queue",
                                    )
                                viewModelScope.launch(Dispatchers.IO) {
                                    workflowRepository.saveExecutionLog(queuedLog)
                                }
                            }
                        },
                    )

                val currentSteps = _activeExecutionState.value?.steps ?: emptyList()
                _activeExecutionState.value =
                    ActiveExecutionState(
                        workflowId = workflow.id,
                        workflowName = workflow.name,
                        steps = currentSteps,
                        isExecuting = false,
                        result = result,
                    )

                val logEntity =
                    WorkflowExecutionLogEntity(
                        id = newExecutionId,
                        workflowId = workflow.id,
                        status =
                            when (result) {
                                is WorkflowExecutionResult.Success -> "SUCCESS"
                                is WorkflowExecutionResult.PreconditionFailed -> if (result.shouldQueue) "QUEUED" else "PRECONDITION_FAILED"
                                is WorkflowExecutionResult.Failed -> "FAILED"
                                is WorkflowExecutionResult.Cancelled -> "CANCELLED"
                            },
                        timestamp = System.currentTimeMillis(),
                        messageId = null,
                        logs = result.steps.toJsonString(),
                        errorReason =
                            when (result) {
                                is WorkflowExecutionResult.Success -> null
                                is WorkflowExecutionResult.PreconditionFailed -> result.reason
                                is WorkflowExecutionResult.Failed -> result.reason
                                is WorkflowExecutionResult.Cancelled -> "Manually cancelled by user"
                            },
                    )
                workflowRepository.saveExecutionLog(logEntity)

                withContext(Dispatchers.Main) {
                    onResult(result)
                }
            }

        ExecutionJobRegistry.registerJob(workflow.id, job)
        return job
    }

    internal suspend fun markNodeAsRetriedInOriginalLog(
        originalLogId: String,
        failedNodeId: String,
        newExecutionId: String,
    ) {
        val originalLog = workflowRepository.getExecutionLogById(originalLogId) ?: return
        val steps = originalLog.logs.toNodeExecutionSteps() ?: return
        val updatedSteps =
            steps.map { step ->
                if (step.nodeId == failedNodeId) {
                    step.copy(retriedInExecutionId = newExecutionId)
                } else {
                    step
                }
            }
        workflowRepository.saveExecutionLog(originalLog.copy(logs = updatedSteps.toJsonString()))
    }

    private fun populateTriggerTestContext(
        triggerNode: WorkflowNode?,
        triggerConfig: JsonObject,
        testContextMap: MutableMap<String, Any>,
    ) {
        val triggerType = triggerNode?.type ?: ""
        when {
            triggerType.contains("MANUAL_TRIGGER", ignoreCase = true) ->
                populateManualTestContext(triggerConfig, testContextMap)
            triggerType.contains("SMS", ignoreCase = true) ->
                populateSmsTestContext(triggerConfig, testContextMap)
            triggerType.contains("NOTIFICATION", ignoreCase = true) ->
                populateNotificationTestContext(triggerConfig, testContextMap)
            triggerType.contains("CALL", ignoreCase = true) ->
                populateCallTestContext(triggerConfig, testContextMap)
            triggerType.contains("CRON", ignoreCase = true) ->
                populateCronTestContext(triggerConfig, testContextMap)
            else ->
                populateGenericTestContext(triggerConfig, testContextMap)
        }
        val nowStr = System.currentTimeMillis().toString()
        if (!testContextMap.containsKey("timestamp")) {
            testContextMap["timestamp"] = nowStr
        }
        testContextMap["received_at"] = System.currentTimeMillis()
        testContextMap["show_workflow_notifications"] = _uiState.value.showWorkflowNotifications
    }

    private fun populateManualTestContext(config: JsonObject, map: MutableMap<String, Any>) {
        if (!map.containsKey("body") && !map.containsKey("text")) {
            val defaultBody = config.optString("testBody", "Manual test execution").ifBlank { "Manual test execution" }
            map["body"] = defaultBody
            map["text"] = defaultBody
        }
    }

    private fun populateSmsTestContext(config: JsonObject, map: MutableMap<String, Any>) {
        if (!map.containsKey("body") && !map.containsKey("text")) {
            val defaultBody = config.optString("testBody", "Your OTP verification code is 849201")
                .ifBlank { "Your OTP verification code is 849201" }
            map["body"] = defaultBody
            map["text"] = defaultBody
            map["message"] = defaultBody
            map["sms_body"] = defaultBody
        }
        if (!map.containsKey("sender")) {
            val defaultSender = config.optString("testSender", "+15551234567").ifBlank { "+15551234567" }
            map["sender"] = defaultSender
            map["header"] = defaultSender
            map["sms_header"] = defaultSender
            map["address"] = defaultSender
        }
    }

    private fun populateNotificationTestContext(config: JsonObject, map: MutableMap<String, Any>) {
        if (!map.containsKey("body") && !map.containsKey("text")) {
            val defaultBody = config.optString("testText", "Bank Alert: OTP Code 849201")
                .ifBlank { "Bank Alert: OTP Code 849201" }
            map["body"] = defaultBody
            map["text"] = defaultBody
        }
        if (!map.containsKey("packageName") && !map.containsKey("package_name")) {
            map["package_name"] = config.optString("testPackageName", "com.example.bankapp").ifBlank { "com.example.bankapp" }
        }
        if (!map.containsKey("title")) {
            map["title"] = config.optString("testTitle", "Bank Alert: OTP Code").ifBlank { "Bank Alert: OTP Code" }
        }
        if (!map.containsKey("sender")) {
            map["sender"] = config.optString("testSender", "NotificationService").ifBlank { "NotificationService" }
        }
    }

    private fun populateCallTestContext(config: JsonObject, map: MutableMap<String, Any>) {
        if (!map.containsKey("caller_number") && !map.containsKey("sender")) {
            val caller = config.optString("testCaller", "+15559876543").ifBlank { "+15559876543" }
            map["caller_number"] = caller
            map["sender"] = caller
            map["header"] = caller
        }
    }

    private fun populateCronTestContext(config: JsonObject, map: MutableMap<String, Any>) {
        if (!map.containsKey("body") && !map.containsKey("text")) {
            val defaultBody = config.optString("testPayload", "Scheduled cron trigger executed")
                .ifBlank { "Scheduled cron trigger executed" }
            map["body"] = defaultBody
            map["text"] = defaultBody
        }
        if (!map.containsKey("sender")) {
            map["sender"] = "CronScheduler"
        }
    }

    private fun populateGenericTestContext(config: JsonObject, map: MutableMap<String, Any>) {
        if (!map.containsKey("body") && !map.containsKey("text")) {
            val defaultBody = config.optString("testBody", "Test execution payload").ifBlank { "Test execution payload" }
            map["body"] = defaultBody
            map["text"] = defaultBody
        }
    }

    fun testRunWorkflow(
        workflow: Workflow,
        onResult: (String) -> Unit,
    ) {
        testRunWorkflowWithProgress(
            workflow = workflow,
            onProgressUpdate = {},
            onResult = { result ->
                val logMsg =
                    when (result) {
                        is WorkflowExecutionResult.Success -> "✅ Success!\n" + result.logs.joinToString("\n")
                        is WorkflowExecutionResult.PreconditionFailed ->
                            "⏳ Precondition Failed: ${result.reason}\n" +
                                result.logs.joinToString("\n")
                        is WorkflowExecutionResult.Failed -> "❌ Failed: ${result.reason}\n" + result.logs.joinToString("\n")
                        is WorkflowExecutionResult.Cancelled ->
                            "🛑 Cancelled: Workflow manually cancelled by user\n" +
                                result.logs.joinToString("\n")
                    }
                onResult(logMsg)
            },
        )
    }

    fun checkPermissionsAndStatus() {
        val context = getApplication<Application>()
        val hasNotifPerm = checkNotificationListenerPermissionInternal()
        _uiState.value =
            _uiState.value.copy(
                isBatteryOptimized = checkBatteryOptimizedInternal(),
                hasNotificationListenerPermission = hasNotifPerm,
            )
        discoverInstalledPlugins()
        viewModelScope.launch(Dispatchers.IO) {
            val currentWorkflows = workflowRepository.getActiveWorkflows()
            currentWorkflows.forEach { wf ->
                val notifTrigger = wf.nodes.find { it.type == NodeType.NOTIFICATION_TRIGGER }
                if (notifTrigger != null && wf.isEnabled) {
                    val config = parseJsonObjectSafe(notifTrigger.configJson)
                    val pkgFilter = config.optString("packageFilter", ".*")
                    val isInstalled =
                        dev.zophop.ui.workflows.AppListHelper
                            .isAppInstalled(context, pkgFilter)

                    if (!hasNotifPerm || !isInstalled) {
                        workflowRepository.setWorkflowEnabled(wf.id, false)
                        if (!isInstalled && pkgFilter != ".*" && !pkgFilter.contains("*")) {
                            postUninstalledAppNotification(context, wf.name, pkgFilter)
                        }
                    }
                }
            }
        }
    }

    private fun postUninstalledAppNotification(
        context: Context,
        workflowName: String,
        packageName: String,
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "zophop_system_alerts"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "System & Security Alerts", NotificationManager.IMPORTANCE_HIGH)
                nm.createNotificationChannel(channel)
            }

            val appLabel =
                dev.zophop.ui.workflows.AppListHelper
                    .getAppNameForPackage(context, packageName)
            val notification =
                androidx.core.app.NotificationCompat
                    .Builder(context, channelId)
                    .setSmallIcon(dev.zophop.R.drawable.ic_notification)
                    .setContentTitle("⚠️ Workflow Disabled: App Uninstalled")
                    .setContentText("Target app '$appLabel' is uninstalled. Workflow '$workflowName' has been disabled.")
                    .setStyle(
                        androidx.core.app.NotificationCompat.BigTextStyle().bigText(
                            "Target app '$appLabel' ($packageName) is not installed on this device. Workflow '$workflowName' was automatically disabled to prevent errors.",
                        ),
                    ).setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()

            nm.notify(workflowName.hashCode(), notification)
        } catch (_: Exception) {
        }
    }

    val backupBridge: dev.zophop.feature.backup.BackupBridge =
        dev.zophop.bridge.AppBackupBridge(
            workflowRepository = workflowRepository,
            credentialRepository = credentialRepository,
            preconditionRepository = preconditionRepository,
            currentWorkflows = { workflows.value },
            currentCredentials = { credentials.value },
            currentPreconditions = { preconditions.value },
            isBiometricEnabled = { _uiState.value.lockCredentialsWithBiometric },
        )

    val backupService: dev.zophop.feature.backup.BackupService =
        dev.zophop.feature.backup.DefaultBackupService(backupBridge)

    suspend fun exportBackupJson(options: dev.zophop.feature.backup.ExportOptions): String =
        backupService.exportBackup(options)

    suspend fun parseImportJson(jsonText: String): dev.zophop.feature.backup.ParsedBackupData =
        backupService.parseImport(jsonText)

    suspend fun decryptImportData(
        data: dev.zophop.feature.backup.ParsedBackupData,
        password: CharSequence,
    ): dev.zophop.feature.backup.ParsedBackupData =
        backupService.decryptImport(data, password)

    suspend fun executeImport(
        data: dev.zophop.feature.backup.ParsedBackupData,
        strategy: dev.zophop.feature.backup.ImportConflictStrategy,
    ): dev.zophop.feature.backup.ImportResult =
        backupService.executeImport(data, strategy)

    private fun checkBatteryOptimizedInternal(): Boolean {
        val context = getApplication<Application>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) == false
    }

    private fun checkNotificationListenerPermissionInternal(): Boolean {
        val context = getApplication<Application>()
        val packageName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && (flat.contains(packageName))
    }

    private fun saveCurrentSettings() {
        val current = _uiState.value
        val settings =
            AppSettings(
                nodes = current.nodes,
                allowlist = current.allowlist,
                keywordRegex = current.keywordRegex,
                senderRegex = current.senderRegex,
                bodyParseRegex = current.bodyParseRegex,
                customMessageTemplate = current.customMessageTemplate,
                expiryMinutes = current.expiryMinutes.toIntOrNull() ?: 10,
                maxRetries = 5,
                isEnabled = current.isEnabled,
                allowInsecureHttp = current.allowInsecureHttp,
                forwardNotifications = current.forwardNotifications,
                showWorkflowNotifications = current.showWorkflowNotifications,
                maxParallelExecutions = current.maxParallelExecutions,
                maxExecutionQueueSize = current.maxExecutionQueueSize,
                autoClearEnabled = current.autoClearEnabled,
                autoClearMinutes = current.autoClearMinutes.toIntOrNull() ?: 1440,
                lockCredentialsWithBiometric = current.lockCredentialsWithBiometric,
                globalWorkflowsEnabled = current.globalWorkflowsEnabled,
                dismissedBiometricBanner = current.dismissedBiometricBanner,
            )
        settingsRepo.saveSettings(settings)
    }
}
