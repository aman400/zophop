package dev.zophop.ui.preconditions

import androidx.compose.runtime.Composable
import dev.zophop.core.ui.components.NoSwipeBottomSheet
import dev.zophop.data.PreconditionEntity
import dev.zophop.domain.engine.NodeExecutionStep
import dev.zophop.domain.engine.PreconditionResult
import dev.zophop.domain.model.Credential

/**
 * Modal Bottom Sheet container wrapper for creating or editing Precondition entities.
 */
@Composable
fun PreconditionBottomSheet(
    precondition: PreconditionEntity?,
    credentials: List<Credential> = emptyList(),
    onTestPreconditionWithProgress: ((PreconditionEntity, (List<NodeExecutionStep>) -> Unit, (PreconditionResult) -> Unit) -> Unit)? = null,
    onCancelTestPrecondition: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onSave: (PreconditionEntity) -> Unit,
) {
    NoSwipeBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        PreconditionEditorScreen(
            precondition = precondition,
            credentials = credentials,
            onTestPreconditionWithProgress = onTestPreconditionWithProgress,
            onCancelTestPrecondition = onCancelTestPrecondition,
            onDismiss = onDismiss,
            onSave = onSave,
        )
    }
}
