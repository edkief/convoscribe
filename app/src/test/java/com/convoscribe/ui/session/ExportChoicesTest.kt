package com.convoscribe.ui.session

import com.convoscribe.export.ExportFormat
import com.convoscribe.export.ExportSection
import com.convoscribe.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportChoicesTest {
    @Test
    fun toggle_andWhatCanBeExported() {
        val all = ExportChoices(ExportFormat.DOCX)
        val none = ExportSection.entries.fold(all) { c, s -> c.toggle(s) }
        assertEquals(emptySet<ExportSection>(), none.sections)
        assertFalse(none.canExport(folderName = null))
        assertTrue(none.toggle(ExportSection.QUESTIONS).canExport(folderName = null))
    }

    @Test
    fun subtitles_needTheTranscript() {
        val summaryOnly = ExportChoices(ExportFormat.SRT, setOf(ExportSection.SUMMARY))
        assertFalse(summaryOnly.allows(ExportSection.SUMMARY))
        assertFalse(summaryOnly.canExport(folderName = null))
        assertTrue(summaryOnly.copy(format = ExportFormat.MARKDOWN).canExport(folderName = null))
    }

    @Test
    fun savingNeedsAFolder() {
        val choices = ExportChoices(toFolder = true)
        assertFalse(choices.canExport(folderName = null))
        assertTrue(choices.canExport(folderName = "Obsidian"))
    }

    @Test
    fun from_readsTheLastChoices() {
        val settings = AppSettings(exportFormat = "vtt", exportSections = "transcript", exportToFolder = true)
        assertEquals(
            ExportChoices(ExportFormat.VTT, setOf(ExportSection.TRANSCRIPT), toFolder = true),
            ExportChoices.from(settings)
        )
    }
}
