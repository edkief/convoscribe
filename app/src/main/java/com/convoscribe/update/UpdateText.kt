package com.convoscribe.update

import android.content.Context
import com.convoscribe.R

/** Why [failed] failed, as a sentence for the banner, Settings and the notification. */
fun updateFailureText(context: Context, failed: UpdateState.Failed): String = when (failed.reason) {
    UpdateFailure.DOWNLOAD -> context.getString(R.string.update_failed_download, failed.detail.orEmpty())
    UpdateFailure.SHA256_MISMATCH -> context.getString(R.string.update_failed_sha256)
    UpdateFailure.UNREADABLE -> context.getString(R.string.update_failed_unreadable)
    UpdateFailure.WRONG_PACKAGE -> context.getString(R.string.update_failed_wrong_package)
    UpdateFailure.NOT_NEWER -> context.getString(R.string.update_failed_not_newer)
    UpdateFailure.SIGNATURE_MISMATCH -> context.getString(R.string.update_failed_signature)
    UpdateFailure.BUSY -> context.getString(R.string.update_failed_busy)
    UpdateFailure.INSTALL -> context.getString(R.string.update_failed_install, failed.detail.orEmpty())
}
