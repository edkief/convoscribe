package com.convoscribe.ui.templates

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.ui.components.PickerField

/**
 * Dropdown of summary [templates] (issue #27), showing the one
 * [selectedKey] resolves to (Meeting when it is unknown or deleted).
 * Used by the new-conversation options, the Summary tab and Settings.
 */
@Composable
fun SummaryTemplatePicker(
    label: String,
    templates: List<SummaryTemplate>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = SummaryTemplates.resolve(selectedKey, templates.filterNot { it.builtIn })
    PickerField(
        label = label,
        value = selected.name,
        onClick = { if (enabled) expanded = true },
        modifier = modifier
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            templates.forEach { template ->
                DropdownMenuItem(
                    text = { Text(text = template.name) },
                    onClick = {
                        expanded = false
                        onSelect(template.key)
                    }
                )
            }
        }
    }
}
