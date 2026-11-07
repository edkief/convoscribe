package com.convoscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.convoscribe.R
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.ServerOffer
import com.convoscribe.settings.ServiceSource
import com.convoscribe.ui.components.ChoiceGroup
import com.convoscribe.ui.components.ConfirmDialog
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme

/**
 * *Use this server's services* in the Sync server section (issue #94):
 * whether it is on, what the server last said it provides, the check
 * under way when it is being turned on, and its actions.
 */
data class ServerServicesUi(
    val enabled: Boolean = false,
    val offer: ServerOffer? = null,
    val check: ServicesCheck? = null,
    val onRequest: () -> Unit = {},
    val onAccept: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onTurnOff: () -> Unit = {}
)

/**
 * The *Use this server's services* switch, its status and its one
 * confirmation. [configured] is whether a server address is typed in;
 * [serverUrl] is named in the confirmation.
 */
@Composable
internal fun ServerServicesBlock(ui: ServerServicesUi, configured: Boolean, serverUrl: String) {
    val checking = ui.check == ServicesCheck.Checking
    SwitchRow(
        label = stringResource(R.string.server_services_label),
        help = stringResource(R.string.server_services_help),
        checked = ui.enabled,
        enabled = configured && !checking,
        onCheckedChange = { on -> if (on) ui.onRequest() else ui.onTurnOff() }
    )
    when (val check = ui.check) {
        ServicesCheck.Checking -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
        ) {
            InlineProgress()
            NoticeText(text = stringResource(R.string.server_services_checking))
        }
        ServicesCheck.Refused -> ErrorBanner(message = stringResource(R.string.server_services_refused))
        is ServicesCheck.Failed -> ErrorBanner(message = stringResource(R.string.server_services_failed, check.message))
        is ServicesCheck.Confirm -> ServerServicesConfirm(check.offer, serverUrl, ui.onAccept, ui.onDismiss)
        null -> Unit
    }
    if (ui.enabled) {
        val offer = ui.offer
        NoticeText(
            text = stringResource(
                R.string.server_services_status_llm,
                offer?.llm?.let { modelName(it) } ?: stringResource(R.string.server_services_not_available)
            )
        )
        NoticeText(
            text = stringResource(
                R.string.server_services_status_asr,
                offer?.transcription?.let { modelName(it) } ?: stringResource(R.string.server_services_not_available)
            )
        )
        NoticeText(
            text = stringResource(
                R.string.server_services_status_decision,
                offer?.decision?.let { modelName(it) } ?: stringResource(R.string.server_services_not_available)
            )
        )
    }
}

@Composable
private fun modelName(service: OfferedService): String =
    service.model.ifBlank { stringResource(R.string.server_services_default_model) }

/** The one confirmation: what [offer] provides, where transcripts and recordings go. */
@Composable
private fun ServerServicesConfirm(offer: ServerOffer, serverUrl: String, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val host = serverUrl.trim()
    val provided = listOfNotNull(
        offer.llm?.let { stringResource(R.string.server_services_confirm_llm, modelName(it)) },
        offer.transcription?.let { stringResource(R.string.server_services_confirm_asr, modelName(it)) },
        offer.decision?.let { stringResource(R.string.server_services_confirm_decision, modelName(it)) }
    )
    if (provided.isEmpty()) {
        // Nothing to accept: the switch stays off, so services the server adds later are asked about then.
        ConfirmDialog(
            title = stringResource(R.string.server_services_confirm_title),
            message = stringResource(R.string.server_services_confirm_none, host),
            confirmLabel = stringResource(R.string.server_services_none_action),
            dismissLabel = stringResource(R.string.sync_confirm_cancel),
            onConfirm = onDismiss,
            onDismiss = onDismiss
        )
        return
    }
    val message = (listOf(stringResource(R.string.server_services_confirm_provides, host)) + provided)
        .joinToString("\n\n") + "\n\n" + stringResource(R.string.server_services_confirm_footer)
    ConfirmDialog(
        title = stringResource(R.string.server_services_confirm_title),
        message = message,
        confirmLabel = stringResource(R.string.server_services_confirm_action),
        dismissLabel = stringResource(R.string.sync_confirm_cancel),
        onConfirm = onAccept,
        onDismiss = onDismiss
    )
}

/**
 * The choice between the sync server's service and the user's own,
 * shown in the Language model, Transcription server and Decision model sections while
 * the server's services are on. With the server's chosen, says what it
 * provides or that it doesn't; the user's own fields are then hidden
 * but kept.
 */
@Composable
internal fun ServiceSourceChoice(
    source: ServiceSource,
    offered: OfferedService?,
    unavailableText: String,
    onSourceChange: (ServiceSource) -> Unit
) {
    ChoiceGroup(
        options = listOf(
            ServiceSource.SERVER to stringResource(R.string.service_source_server),
            ServiceSource.OWN to stringResource(R.string.service_source_own)
        ),
        selected = source,
        onSelect = onSourceChange
    )
    if (source == ServiceSource.SERVER) {
        if (offered != null) {
            NoticeText(text = stringResource(R.string.service_source_server_in_use, modelName(offered)))
        } else {
            NoticeText(text = unavailableText, isError = true)
        }
    }
}
