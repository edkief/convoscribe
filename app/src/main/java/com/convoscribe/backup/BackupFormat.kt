package com.convoscribe.backup

import com.convoscribe.data.SessionManifest
import com.convoscribe.settings.AppSettings
import kotlinx.serialization.Serializable

/**
 * What a ConvoScribe backup holds (issue #25). The archive is a zip:
 *
 *  1. [HEADER_ENTRY] first, a [BackupHeader]: the format and its
 *     version, then everything that is not a session (settings, known
 *     voices, summary templates, presets);
 *  2. for each session, `sessions/<syncId>.json` (a [SessionEntry]),
 *     followed by its recordings, `recordings/<sha256>.wav`, stored
 *     uncompressed.
 *
 * Reading in that order needs one pass over the stream: the header is
 * checked before anything changes, and each session's recordings
 * arrive right after it, so a restore never holds more than one
 * session's files.
 */
object BackupFormat {
    /** [BackupHeader.format] of every backup. */
    const val FORMAT: String = "convoscribe-backup"

    /** The newest [BackupHeader.version] this app reads and the one it writes. */
    const val VERSION: Int = 1

    const val HEADER_ENTRY: String = "backup.json"
    const val SESSION_PREFIX: String = "sessions/"
    const val RECORDING_PREFIX: String = "recordings/"

    fun sessionEntry(syncId: String): String = "$SESSION_PREFIX$syncId.json"

    fun recordingEntry(sha256: String): String = "$RECORDING_PREFIX$sha256.wav"

    /** The SHA-256 named by a recording entry, or `null` for another entry. */
    fun recordingSha(name: String): String? = name.takeIf { it.startsWith(RECORDING_PREFIX) && it.endsWith(".wav") }
        ?.removePrefix(RECORDING_PREFIX)?.removeSuffix(".wav")
}

/** The first entry of a backup. Never holds an API key. */
@Serializable
data class BackupHeader(
    val format: String,
    val version: Int,
    val createdAt: Long,
    val appVersion: String = "",
    val sessionCount: Int = 0,
    val settings: PortableSettings? = null,
    val voices: List<Voice> = emptyList(),
    val templates: List<Template> = emptyList(),
    val presets: List<PresetDoc> = emptyList()
) {
    /**
     * A known voice sample: [embedding] is [com.convoscribe.data.EmbeddingCodec]'s
     * bytes in Base64. [syncId], here and on [Template] and [PresetDoc],
     * keeps the item's identity for sync (issue #70); older backups have none.
     */
    @Serializable
    data class Voice(val name: String, val embedding: String, val createdAt: Long, val syncId: String? = null)

    /** A summary template the user wrote. */
    @Serializable
    data class Template(val name: String, val body: String, val createdAt: Long, val syncId: String? = null)

    /** A preset, with its tags by name and its template by reference. */
    @Serializable
    data class PresetDoc(
        val syncId: String? = null,
        val name: String,
        val titlePattern: String = "",
        val language: String,
        val numSpeakers: Int = -1,
        val participants: List<String>? = null,
        val tags: List<String> = emptyList(),
        val summaryTemplate: SessionManifest.TemplateRef? = null,
        val cleanAudio: Boolean = false
    )
}

/** One session in a backup: its manifest and when it last changed. */
@Serializable
data class SessionEntry(val updatedAt: Long, val manifest: SessionManifest)

/**
 * The settings a backup carries: those that make sense on another
 * device. The API keys live apart, in [com.convoscribe.settings.SecretStore],
 * and are never read. The ASR engine and thread count are left out
 * since they depend on the device, and so is whether the
 * floating-control prompt was shown.
 */
@Serializable
data class PortableSettings(
    val llmBaseUrl: String,
    val llmModel: String,
    val maxTranscriptChars: Int,
    val asrLanguage: String,
    val asrServerUrl: String,
    val asrServerModel: String,
    val asrServerAuto: Boolean,
    val numSpeakers: Int,
    val clusterThreshold: Float,
    val speakerMatchThreshold: Float,
    val speakerIdMode: String,
    val semanticIndexMode: String,
    val diarizationEmbedding: String,
    val diarizationStepSeconds: Float,
    val defaultParticipants: List<String>? = null,
    val defaultSummaryTemplate: SessionManifest.TemplateRef? = null,
    val cleanAudioDefault: Boolean,
    val autoMarkNotes: Boolean,
    val autoTitle: Boolean,
    val displayMode: String,
    val dynamicColor: Boolean,
    val floatingControl: Boolean,
    val floatingShape: String? = null,
    val updateCheckAuto: Boolean,
    val updateIncludePrereleases: Boolean
) {
    /**
     * [current] with these settings applied; [templateKey] resolves the
     * default template, which stays as it is when it cannot be found.
     */
    fun applyTo(current: AppSettings, templateKey: (SessionManifest.TemplateRef) -> String?): AppSettings =
        current.copy(
            llmBaseUrl = llmBaseUrl,
            llmModel = llmModel,
            maxTranscriptChars = maxTranscriptChars,
            asrLanguage = asrLanguage,
            asrServerUrl = asrServerUrl,
            asrServerModel = asrServerModel,
            asrServerAuto = asrServerAuto,
            numSpeakers = numSpeakers,
            clusterThreshold = clusterThreshold,
            speakerMatchThreshold = speakerMatchThreshold,
            speakerIdMode = speakerIdMode,
            semanticIndexMode = semanticIndexMode,
            diarizationEmbedding = diarizationEmbedding,
            diarizationStepSeconds = diarizationStepSeconds,
            defaultParticipants = defaultParticipants,
            defaultSummaryTemplate = defaultSummaryTemplate?.let(templateKey) ?: current.defaultSummaryTemplate,
            cleanAudioDefault = cleanAudioDefault,
            autoMarkNotes = autoMarkNotes,
            autoTitle = autoTitle,
            displayMode = displayMode,
            dynamicColor = dynamicColor,
            floatingControl = floatingControl,
            floatingShape = floatingShape,
            updateCheckAuto = updateCheckAuto,
            updateIncludePrereleases = updateIncludePrereleases
        )

    companion object {
        /** The portable part of [settings]; [templateRef] names its default template. */
        fun from(settings: AppSettings, templateRef: (String) -> SessionManifest.TemplateRef?): PortableSettings =
            PortableSettings(
                llmBaseUrl = settings.llmBaseUrl,
                llmModel = settings.llmModel,
                maxTranscriptChars = settings.maxTranscriptChars,
                asrLanguage = settings.asrLanguage,
                asrServerUrl = settings.asrServerUrl,
                asrServerModel = settings.asrServerModel,
                asrServerAuto = settings.asrServerAuto,
                numSpeakers = settings.numSpeakers,
                clusterThreshold = settings.clusterThreshold,
                speakerMatchThreshold = settings.speakerMatchThreshold,
                speakerIdMode = settings.speakerIdMode,
                semanticIndexMode = settings.semanticIndexMode,
                diarizationEmbedding = settings.diarizationEmbedding,
                diarizationStepSeconds = settings.diarizationStepSeconds,
                defaultParticipants = settings.defaultParticipants,
                defaultSummaryTemplate = templateRef(settings.defaultSummaryTemplate),
                cleanAudioDefault = settings.cleanAudioDefault,
                autoMarkNotes = settings.autoMarkNotes,
                autoTitle = settings.autoTitle,
                displayMode = settings.displayMode,
                dynamicColor = settings.dynamicColor,
                floatingControl = settings.floatingControl,
                floatingShape = settings.floatingShape,
                updateCheckAuto = settings.updateCheckAuto,
                updateIncludePrereleases = settings.updateIncludePrereleases
            )
    }
}
