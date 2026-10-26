package com.convoscribe.recording

import com.convoscribe.data.Preset

/**
 * The options a recording made from this preset starts with (issue
 * #32). A summary template deleted since the preset was saved falls
 * back to [defaultTemplate]; deleted tags are already gone from
 * [Preset.tags].
 */
fun Preset.toOptions(templateKeys: Collection<String>, defaultTemplate: String): ConversationOptions =
    ConversationOptions(
        language = language,
        numSpeakers = numSpeakers,
        participants = participants,
        title = titlePattern,
        summaryTemplate = summaryTemplate.takeIf { it in templateKeys } ?: defaultTemplate,
        cleanAudio = cleanAudio,
        tags = tags
    )

/** These options saved as a preset named [name]; [title] becomes its title pattern. */
fun ConversationOptions.toPreset(name: String, id: Long = 0): Preset = Preset(
    id = id,
    name = name,
    titlePattern = title,
    language = language,
    numSpeakers = numSpeakers,
    participants = participants,
    tags = tags,
    summaryTemplate = summaryTemplate,
    cleanAudio = cleanAudio
)
