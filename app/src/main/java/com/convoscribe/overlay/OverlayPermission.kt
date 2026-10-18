package com.convoscribe.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.ui.components.ConfirmDialog

/**
 * The "Display over other apps" permission the floating recording
 * control needs (issue #7). It is a special access granted in system
 * Settings, not a runtime dialog, so the app explains it first and
 * then opens the system page for ConvoScribe.
 */
object OverlayPermission {
    fun granted(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** The system "Display over other apps" page for this app. */
    fun settingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Why the floating control needs the permission, with "Allow" (opens
 * the system page) and "Not now". Recording works either way; without
 * the permission the notification stays the only control.
 */
@Composable
fun OverlayPermissionDialog(onAllow: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.overlay_permission_title),
        message = stringResource(R.string.overlay_permission_message),
        confirmLabel = stringResource(R.string.overlay_permission_allow),
        dismissLabel = stringResource(R.string.overlay_permission_not_now),
        onConfirm = onAllow,
        onDismiss = onDismiss
    )
}
