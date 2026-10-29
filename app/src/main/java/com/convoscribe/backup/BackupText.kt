package com.convoscribe.backup

import android.content.Context
import com.convoscribe.R

/** "Backup saved" or "Restore failed": the title of a finished run, for Settings and the notification. */
fun backupResultTitle(context: Context, state: BackupState): String? = when (state) {
    is BackupState.Done -> context.getString(
        if (state.kind == BackupKind.BACKUP) R.string.backup_done_title else R.string.restore_done_title
    )
    is BackupState.Failed -> context.getString(
        if (state.kind == BackupKind.BACKUP) R.string.backup_failed_title else R.string.restore_failed_title
    )
    else -> null
}

/** What a finished run did, or why it failed, as one or two sentences. */
fun backupResultText(context: Context, state: BackupState): String? {
    val res = context.resources
    return when (state) {
        is BackupState.Done -> when (state.kind) {
            BackupKind.BACKUP -> res.getQuantityString(R.plurals.backup_done_sessions, state.sessions, state.sessions)
            BackupKind.RESTORE -> listOfNotNull(
                res.getQuantityString(R.plurals.restore_done_sessions, state.sessions, state.sessions),
                state.skipped.takeIf { it > 0 }?.let {
                    res.getQuantityString(R.plurals.restore_skipped_sessions, it, it)
                }
            ).joinToString(" ")
        }
        is BackupState.Failed -> listOfNotNull(
            when (state.failure) {
                BackupFailure.NOT_A_BACKUP -> context.getString(R.string.backup_failure_not_a_backup)
                BackupFailure.TOO_NEW -> context.getString(R.string.backup_failure_too_new)
                BackupFailure.DAMAGED -> context.getString(R.string.backup_failure_damaged)
                BackupFailure.IO -> context.getString(R.string.backup_failure_io, state.message.orEmpty())
            },
            state.restored.takeIf { it > 0 }?.let {
                res.getQuantityString(R.plurals.restore_partial_sessions, it, it)
            }
        ).joinToString(" ")
        else -> null
    }
}
