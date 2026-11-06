package com.convoscribe.export

/** Renders a session in the chosen format (issue #29). */
object ExportRenderer {
    /** [session] as [options] asks, as the bytes of the file. */
    fun render(session: ExportSession, options: ExportOptions): ByteArray = when (options.format) {
        ExportFormat.TEXT -> TextExport.render(session, options).toByteArray()
        ExportFormat.MARKDOWN -> MarkdownExport.render(session, options).toByteArray()
        ExportFormat.SRT -> SubtitleExport.srt(session).toByteArray()
        ExportFormat.VTT -> SubtitleExport.vtt(session).toByteArray()
        ExportFormat.JSON -> JsonExport.render(session, options).toByteArray()
        ExportFormat.DOCX -> DocxWriter.write(ExportDocument.build(session, options))
        ExportFormat.PDF -> PdfWriter.write(ExportDocument.build(session, options), session.title)
    }
}
