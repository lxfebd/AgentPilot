package com.agentpilot.app.ui.designsystem

import com.agentpilot.app.R
import com.agentpilot.app.api.ApiException
import com.agentpilot.app.feature.sessiondetail.NativeHistoryRejected
import com.agentpilot.app.feature.sessions.NewSessionDraftInvalid
import com.agentpilot.app.feature.sessions.RuntimeOperationFailure
import com.agentpilot.app.feature.sessions.RuntimeWorkflowFailure

/**
 * Error text for runtime operations. Stable codes come from the server's
 * [com.agentpilot.app.api.ApiException]; workflow rejections use the
 * workflow strings; everything else falls back to the raw message.
 */
internal fun runtimeOperationErrorMessage(
    context: android.content.Context,
    error: Throwable,
    @androidx.annotation.StringRes fallback: Int = R.string.runtime_operation_failed,
): String = when (error) {
    is ApiException -> error.message ?: context.getString(fallback)
    is RuntimeWorkflowFailure -> context.getString(
        when (error.reason) {
            RuntimeWorkflowFailure.Reason.TargetChanged -> R.string.runtime_workflow_target_changed
            RuntimeWorkflowFailure.Reason.BindingInProgress -> R.string.runtime_workflow_binding
            RuntimeWorkflowFailure.Reason.CreationMissing -> R.string.runtime_workflow_creation_missing
            RuntimeWorkflowFailure.Reason.SessionMismatch -> R.string.runtime_workflow_session_mismatch
        },
    )
    is NativeHistoryRejected -> context.getString(R.string.native_history_rejected)
    is NewSessionDraftInvalid -> context.getString(
        when (error.reason) {
            NewSessionDraftInvalid.Reason.ConnectorRequired -> R.string.new_session_connector_required
            NewSessionDraftInvalid.Reason.WorkspaceRequired -> R.string.new_session_workspace_required
            NewSessionDraftInvalid.Reason.RuntimeTypeInvalid -> R.string.new_session_runtime_type_invalid
            else -> R.string.new_session_create_failed
        },
    )
    is RuntimeOperationFailure -> context.getString(R.string.runtime_operation_failed)
    else -> error.message ?: context.getString(fallback)
}

/** Text for a stable runtime/workflow error code, or the caller's fallback. */
internal fun runtimeErrorText(context: android.content.Context, code: String?, fallback: String?): String? =
    runtimeErrorRes(code)?.let(context::getString) ?: fallback
