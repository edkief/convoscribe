package com.convoscribe.export

import com.convoscribe.asr.TimedToken
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals

/** A French meeting with a renamed speaker and every part an export can hold. */
object ExportFixtures {
    val zone: ZoneId = ZoneId.of("Europe/Paris")
    val start: Long = LocalDateTime.of(2026, 11, 6, 14, 30).atZone(zone).toInstant().toEpochMilli()

    /** Word tokens spread evenly over [startMs]..[endMs], as the recogniser writes them. */
    fun tokens(text: String, startMs: Long, endMs: Long): List<TimedToken> {
        val words = text.split(' ')
        val step = (endMs - startMs) / words.size
        return words.mapIndexed { i, word -> TimedToken("▁$word", startMs + i * step) }
    }

    private const val LONG = "D'accord. Les dépenses de déplacement ont baissé de dix pour cent cette année, " +
        "ce qui nous laisse une marge pour le recrutement prévu au printemps."

    val lines: List<TranscriptLine> = listOf(
        TranscriptLine(
            startMs = 0L,
            endMs = 4_200L,
            speaker = "Élodie",
            speakerIndex = 0,
            text = "Bonjour à tous, on commence par le budget.",
            id = 1,
            tokens = tokens("Bonjour à tous, on commence par le budget.", 0L, 4_200L)
        ),
        TranscriptLine(
            startMs = 4_200L,
            endMs = 16_800L,
            speaker = "Speaker 2",
            speakerIndex = 1,
            text = LONG,
            id = 2,
            tokens = tokens(LONG, 4_200L, 16_800L)
        ),
        TranscriptLine(startMs = 17_000L, endMs = 17_000L, speaker = null, text = "Merci.", id = 3),
        TranscriptLine(
            startMs = 19_000L,
            endMs = 22_500L,
            speaker = "Élodie",
            speakerIndex = 0,
            text = "On envoie le compte rendu <vendredi> & on clôt.",
            id = 4,
            edited = true
        )
    )

    fun session(): ExportSession = ExportSession(
        title = "Point d'équipe — budget",
        createdAt = start,
        durationMs = 754_000L,
        language = "fr",
        speakers = listOf(ExportSpeaker(1, "Speaker 2"), ExportSpeaker(0, "Élodie")),
        participants = listOf("Élodie", "Marc"),
        tags = listOf("équipe", "budget"),
        lines = lines,
        marks = listOf(
            TranscriptMark(id = 2, atMs = 19_500L),
            TranscriptMark(
                id = 1,
                atMs = 5_000L,
                note = "chiffres déplacements",
                expandedNote = "Les déplacements baissent de 10 %.\nUne marge pour recruter."
            )
        ),
        summary = ExportSummary(
            content = "# Résumé\n\n- Budget déplacements **en baisse** de 10 %\n- Recrutement au printemps\n\n" +
                "## Décisions\n\n1. Envoyer le compte rendu vendredi",
            templateName = "Réunion",
            createdAt = start + 20 * 60_000L
        ),
        actionItems = listOf(
            ExportActionItem("Envoyer le compte rendu", "Élodie", "2026-11-13", 19_000L, done = false),
            ExportActionItem("Réserver la salle", null, null, null, done = true)
        ),
        talkingPoints = listOf(
            ExportTalkingPoint("Budget", covered = true, coveredAtMs = 1_000L),
            ExportTalkingPoint("Recrutement", covered = false, coveredAtMs = null)
        ),
        questions = listOf(
            ExportQuestion("Quel est le pourcentage ?", "Dix pour cent (**10 %**).", start + 30 * 60_000L),
            ExportQuestion("Et pour l'an prochain ?", null, start + 31 * 60_000L)
        ),
        zone = zone
    )

    /**
     * Compare [actual] with the golden file `export/<name>` on the test
     * classpath. On a mismatch the actual output is written to
     * `build/export-golden/<name>` to review and copy over.
     */
    fun assertGolden(name: String, actual: ByteArray) {
        val expected = ExportFixtures::class.java.getResourceAsStream("/export/$name")?.use { it.readBytes() }
        if (expected == null || !expected.contentEquals(actual)) {
            val out = File("build/export-golden/$name").apply { parentFile?.mkdirs() }
            out.writeBytes(actual)
            if (expected == null) throw AssertionError("No golden file export/$name; wrote ${out.absolutePath}")
            if (name.endsWith(".pdf") || name.endsWith(".docx")) {
                throw AssertionError("export/$name differs; wrote ${out.absolutePath}")
            }
            assertEquals("export/$name differs; wrote ${out.absolutePath}", String(expected), String(actual))
        }
    }

    fun assertGolden(name: String, actual: String) = assertGolden(name, actual.toByteArray(Charsets.UTF_8))
}
