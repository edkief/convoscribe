package com.convoscribe.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.convoscribe.R
import com.convoscribe.settings.ServiceSource
import com.convoscribe.settings.isUnencryptedUrl
import com.convoscribe.ui.components.Disclosure
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.theme.AppTheme

/**
 * "Decision model" section of Settings → AI (issue #101): a laya
 * decision service for talking-point checks. Like Transcription server:
 * the choice between the sync server's and the user's own while the
 * server's services are on, then the user's own address, key and laya
 * model with *Test connection*. *Check talking points with the decision
 * model* shows once one is set up, whichever it is, so the language
 * model can keep the checks.
 */
@Composable
internal fun DecisionModelSection(state: SettingsUiState, actions: SettingsActions) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_decision)
    ) {
        SupportingText(text = stringResource(R.string.settings_decision_help))
        if (state.serverServicesEnabled) {
            ServiceSourceChoice(
                source = state.decisionSource,
                offered = state.serverOffer?.decision,
                unavailableText = stringResource(R.string.service_source_decision_unavailable),
                onSourceChange = actions.onDecisionSourceChange
            )
        }
        // The user's own service, kept while the sync server's is used.
        if (!state.serverServicesEnabled || state.decisionSource == ServiceSource.OWN) {
            Disclosure(
                title = stringResource(R.string.settings_decision_own),
                summary = state.form.decisionUrl.takeIf { it.isNotBlank() }?.let { serverHost(it) }
                    ?: stringResource(R.string.settings_server_own_none),
                outlined = false
            ) {
                OwnDecisionFields(state = state, actions = actions)
            }
        }
        if (state.decisionConfigured) {
            SwitchRow(
                label = stringResource(R.string.settings_decision_for_talking_points_label),
                help = stringResource(R.string.settings_decision_for_talking_points_help),
                checked = state.decisionForTalkingPoints,
                onCheckedChange = actions.onDecisionForTalkingPointsChange
            )
        }
    }
}

/** The user's own decision service: address, key, laya model and *Test connection*. */
@Composable
private fun OwnDecisionFields(state: SettingsUiState, actions: SettingsActions) {
    val form = state.form
    val onFormChange = actions.onFormChange
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = form.decisionUrl,
        onValueChange = { onFormChange(form.copy(decisionUrl = it)) },
        label = { Text(text = stringResource(R.string.settings_llm_base_url_label)) },
        placeholder = { Text(text = stringResource(R.string.settings_decision_url_placeholder)) },
        singleLine = true,
        supportingText = if (isUnencryptedUrl(form.decisionUrl)) {
            { Text(text = stringResource(R.string.settings_decision_url_cleartext)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = form.decisionKey,
        onValueChange = { onFormChange(form.copy(decisionKey = it)) },
        label = { Text(text = stringResource(R.string.settings_server_key_label)) },
        singleLine = true,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Text(
                    text = stringResource(
                        if (keyVisible) R.string.settings_llm_api_key_hide else R.string.settings_llm_api_key_show
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = form.decisionModel,
        onValueChange = { onFormChange(form.copy(decisionModel = it)) },
        label = { Text(text = stringResource(R.string.settings_llm_model_label)) },
        placeholder = { Text(text = stringResource(R.string.settings_decision_model_placeholder)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
    ) {
        OutlinedButton(
            onClick = actions.onTestDecision,
            enabled = !state.decisionTesting && form.decisionUrl.isNotBlank(),
            modifier = Modifier.weight(1f)
        ) {
            Text(text = stringResource(R.string.settings_llm_test))
        }
        if (state.decisionTesting) InlineProgress()
    }
    when (val result = state.decisionTestResult) {
        is ConnectionTestResult.Connected -> NoticeText(
            text = stringResource(R.string.settings_decision_test_connected, result.models.firstOrNull().orEmpty())
        )
        is ConnectionTestResult.Failed -> ErrorBanner(
            message = stringResource(R.string.settings_test_failed, result.message)
        )
        null -> Unit
    }
}
