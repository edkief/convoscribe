package com.convoscribe.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.convoscribe.R
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.TalkingPointReasoning
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.ServiceSource
import com.convoscribe.ui.components.Disclosure
import com.convoscribe.ui.components.ErrorBanner
import com.convoscribe.ui.components.InlineProgress
import com.convoscribe.ui.components.NavRow
import com.convoscribe.ui.components.NoticeText
import com.convoscribe.ui.components.SectionCard
import com.convoscribe.ui.components.SupportingText
import com.convoscribe.ui.components.SwitchRow
import com.convoscribe.ui.templates.SummaryTemplatePicker
import com.convoscribe.ui.theme.AppTheme

/**
 * "Language model" section: base URL, API key (with show/hide
 * toggle), model name, max transcript characters, the "Test
 * connection" button and the formatted result line. Model id
 * chips appear below the result when the last `listModels` call
 * returned at least one id. While the sync server's services are on
 * ([serverServices], issue #94) it starts with the choice between the
 * server's language model and the user's own; the user's own fields
 * and test show only when it is chosen.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
internal fun LanguageModelSection(
    form: SettingsForm,
    testing: Boolean,
    testResult: ConnectionTestResult?,
    availableModels: List<String>,
    onFormChange: (SettingsForm) -> Unit,
    onTestConnection: () -> Unit,
    serverServices: Boolean = false,
    source: ServiceSource = ServiceSource.OWN,
    offered: OfferedService? = null,
    onSourceChange: (ServiceSource) -> Unit = {}
) {
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_llm)
    ) {
        if (serverServices) {
            ServiceSourceChoice(
                source = source,
                offered = offered,
                unavailableText = stringResource(R.string.service_source_llm_unavailable),
                onSourceChange = onSourceChange
            )
        }
        // The user's own endpoint, kept while the server's is used (issue #94).
        val own = !serverServices || source == ServiceSource.OWN
        if (own) {
            OwnLanguageModelFields(
                form = form,
                apiKeyVisible = apiKeyVisible,
                onApiKeyVisibleChange = { apiKeyVisible = it },
                onFormChange = onFormChange
            )
        }
        if (own) {
            LanguageModelTest(
                form = form,
                testing = testing,
                testResult = testResult,
                availableModels = availableModels,
                onFormChange = onFormChange,
                onTestConnection = onTestConnection
            )
        }
    }
}

/** The user's own language model: base URL, API key (with show/hide) and model. */
@Composable
private fun OwnLanguageModelFields(
    form: SettingsForm,
    apiKeyVisible: Boolean,
    onApiKeyVisibleChange: (Boolean) -> Unit,
    onFormChange: (SettingsForm) -> Unit
) {
    OutlinedTextField(
        value = form.baseUrl,
        onValueChange = { onFormChange(form.copy(baseUrl = it)) },
        label = { Text(text = stringResource(R.string.settings_llm_base_url_label)) },
        placeholder = { Text(text = stringResource(R.string.settings_llm_base_url_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = form.apiKey,
        onValueChange = { onFormChange(form.copy(apiKey = it)) },
        label = { Text(text = stringResource(R.string.settings_llm_api_key_label)) },
        singleLine = true,
        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { onApiKeyVisibleChange(!apiKeyVisible) }) {
                Text(
                    text = stringResource(
                        if (apiKeyVisible) R.string.settings_llm_api_key_hide else R.string.settings_llm_api_key_show
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = form.model,
        onValueChange = { onFormChange(form.copy(model = it)) },
        label = { Text(text = stringResource(R.string.settings_llm_model_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * "Test connection" for the user's own language model, its result line
 * and the model id chips that fill the Model field on tap.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LanguageModelTest(
    form: SettingsForm,
    testing: Boolean,
    testResult: ConnectionTestResult?,
    availableModels: List<String>,
    onFormChange: (SettingsForm) -> Unit,
    onTestConnection: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.m)
    ) {
        OutlinedButton(
            onClick = onTestConnection,
            enabled = !testing,
            modifier = Modifier.weight(1f)
        ) {
            Text(text = stringResource(R.string.settings_llm_test))
        }
        if (testing) InlineProgress()
    }
    when (testResult) {
        is ConnectionTestResult.Connected -> NoticeText(
            text = pluralStringResource(
                R.plurals.settings_test_connected,
                testResult.count,
                testResult.count,
                testResult.models.joinToString(", ")
            )
        )
        is ConnectionTestResult.Failed -> ErrorBanner(
            message = stringResource(R.string.settings_test_failed, testResult.message)
        )
        null -> SupportingText(text = stringResource(R.string.settings_llm_test_idle))
    }
    if (availableModels.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.xs)
        ) {
            availableModels.forEach { id ->
                SuggestionChip(
                    onClick = { onFormChange(form.copy(model = id)) },
                    label = { Text(text = id) }
                )
            }
        }
    }
}

/**
 * "Summaries" section (issue #27): the template new conversations
 * start with (written immediately), Templates to add or edit them, and
 * whether finished conversations are summarised automatically (issue
 * #29) and the notes of marks written once speakers are identified
 * (issue #48), both written immediately.
 */
@Composable
internal fun SummariesSection(
    templates: List<SummaryTemplate>,
    defaultKey: String,
    onDefaultChange: (String) -> Unit,
    onOpenTemplates: () -> Unit,
    autoMarkNotes: Boolean,
    onAutoMarkNotesChange: (Boolean) -> Unit,
    autoSummary: Boolean = false,
    onAutoSummaryChange: (Boolean) -> Unit = {},
    autoTitle: Boolean,
    onAutoTitleChange: (Boolean) -> Unit
) {
    SectionCard(
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth),
        title = stringResource(R.string.settings_section_summaries)
    ) {
        SummaryTemplatePicker(
            label = stringResource(R.string.settings_summaries_default_label),
            templates = templates,
            selectedKey = defaultKey,
            onSelect = onDefaultChange
        )
        SupportingText(text = stringResource(R.string.settings_summaries_default_help))
        NavRow(
            label = stringResource(R.string.settings_summaries_templates_row),
            onClick = onOpenTemplates
        )
        SwitchRow(
            label = stringResource(R.string.settings_auto_summary_label),
            help = stringResource(R.string.settings_auto_summary_help),
            checked = autoSummary,
            onCheckedChange = onAutoSummaryChange
        )
        SwitchRow(
            label = stringResource(R.string.settings_auto_mark_notes_label),
            help = stringResource(R.string.settings_auto_mark_notes_help),
            checked = autoMarkNotes,
            onCheckedChange = onAutoMarkNotesChange
        )
        SwitchRow(
            label = stringResource(R.string.settings_auto_title_label),
            help = stringResource(R.string.settings_auto_title_help),
            checked = autoTitle,
            onCheckedChange = onAutoTitleChange
        )
    }
}

/**
 * The language model's expert settings: how much of a transcript is
 * sent, how often talking points are checked while recording, and how
 * those checks ask for little reasoning (issue #136): set here for the
 * user's own model, by the server for its own ([llmFromServer]).
 */
@Composable
internal fun AiAdvanced(form: SettingsForm, llmFromServer: Boolean, onFormChange: (SettingsForm) -> Unit) {
    Disclosure(
        title = stringResource(R.string.settings_advanced),
        summary = stringResource(R.string.settings_ai_advanced_summary, form.maxChars, form.talkingPointSeconds),
        modifier = Modifier.widthIn(max = AppTheme.sizes.contentMaxWidth)
    ) {
        OutlinedTextField(
            value = form.maxChars,
            onValueChange = { onFormChange(form.copy(maxChars = it)) },
            label = { Text(text = stringResource(R.string.settings_llm_max_chars_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = form.talkingPointSeconds,
            onValueChange = { onFormChange(form.copy(talkingPointSeconds = it)) },
            label = { Text(text = stringResource(R.string.settings_talking_point_check_label)) },
            supportingText = { Text(text = stringResource(R.string.settings_talking_point_check_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        if (llmFromServer) {
            SupportingText(text = stringResource(R.string.settings_talking_point_reasoning_server))
        } else {
            SwitchRow(
                label = stringResource(R.string.settings_talking_point_low_reasoning_label),
                help = stringResource(R.string.settings_talking_point_low_reasoning_help),
                checked = form.talkingPointLowReasoning,
                onCheckedChange = { onFormChange(form.copy(talkingPointLowReasoning = it)) }
            )
            if (form.talkingPointLowReasoning) {
                val invalid = TalkingPointReasoning.parseFields(form.talkingPointExtraFields) == null
                OutlinedTextField(
                    value = form.talkingPointExtraFields,
                    onValueChange = { onFormChange(form.copy(talkingPointExtraFields = it)) },
                    label = { Text(text = stringResource(R.string.settings_talking_point_extra_fields_label)) },
                    placeholder = { Text(text = EXTRA_FIELDS_EXAMPLE) },
                    isError = invalid,
                    supportingText = {
                        Text(
                            text = stringResource(
                                if (invalid) {
                                    R.string.settings_talking_point_extra_fields_invalid
                                } else {
                                    R.string.settings_talking_point_extra_fields_help
                                }
                            )
                        )
                    },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** What the extra fields look like, for a llama.cpp model with a thinking switch. */
private const val EXTRA_FIELDS_EXAMPLE = "{\"chat_template_kwargs\": {\"enable_thinking\": false}}"
