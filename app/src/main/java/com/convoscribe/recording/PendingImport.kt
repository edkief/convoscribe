package com.convoscribe.recording

import android.net.Uri

/**
 * An audio file waiting to be imported (issue #3): shared or opened
 * with ConvoScribe, or picked on the home screen. The home screen
 * starts it, or first sends the user to setup when the models are
 * missing ([sentToSetup]), and resumes it on the way back.
 */
data class PendingImport(val uri: Uri, val sentToSetup: Boolean = false)
