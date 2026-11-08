package com.convoscribe.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionFilterTest {

    private fun yes(vararg lines: String) = lines.forEach { assertTrue(it, QuestionFilter.isCandidate(it)) }
    private fun no(vararg lines: String) = lines.forEach { assertFalse(it, QuestionFilter.isCandidate(it)) }

    @Test
    fun englishQuestionsWithAQuestionMark() {
        yes(
            "What did we agree last time on the launch date?",
            "We moved the review to Thursday?",
            "So the budget is still forty thousand?"
        )
    }

    @Test
    fun englishQuestionsWithoutPunctuation() {
        yes(
            "what did we agree last time on the launch date",
            "When is the next release going out",
            "how much did the vendor quote us",
            "Did we send the contract to legal",
            "can someone remind me who owns the migration",
            "Is the demo still on for Friday",
            "Did Marie send the slides already",
            "so who is taking notes today",
            "do we have a date for the launch",
            "Don't you think the price is too high"
        )
    }

    @Test
    fun englishStatementsAndOrdersAreNotCandidates() {
        no(
            "We agreed on the fourteenth for the launch.",
            "Do this before the end of the week please",
            "Don't forget the slides for tomorrow",
            "Can't wait to see the numbers next week",
            "I think the budget is fine"
        )
    }

    @Test
    fun frenchQuestionsWithAQuestionMark() {
        yes(
            "On avait dit quelle date pour le lancement ?",
            "Tu as envoyé le contrat au juridique ?"
        )
    }

    @Test
    fun frenchQuestionsWithoutPunctuation() {
        yes(
            "qu'est-ce qu'on avait décidé pour le budget",
            "Est-ce que le client a validé la maquette",
            "quand est-ce qu'on livre la version deux",
            "Comment on fait pour la migration des données",
            "pourquoi le test a échoué hier soir",
            "qui s'occupe du compte rendu aujourd'hui",
            "Avez-vous reçu le devis du fournisseur",
            "et quand partez-vous en vacances cette année",
            "Va-t-il présenter les chiffres demain",
            "Alors combien coûte la licence par an",
            "tu sais est-ce que la salle est réservée"
        )
    }

    @Test
    fun frenchStatementsAreNotCandidates() {
        no(
            "On a validé la maquette hier soir.",
            "Le rendez-vous est fixé à dix heures demain",
            "Peut-être qu'on pourra le faire demain"
        )
    }

    @Test
    fun rhetoricalTagsAreNotQuestions() {
        no(
            "We said Tuesday for the launch, right?",
            "It was a really long meeting, you know?",
            "That went pretty well overall, didn't it, yeah?",
            "On avait dit mardi pour le lancement, n'est-ce pas ?",
            "C'était une très longue réunion, tu vois ?",
            "On part demain matin, non ?",
            "Ça s'est bien passé finalement, hein ?",
            "you know?",
            "right?",
            "n'est-ce pas ?"
        )
    }

    @Test
    fun aRealQuestionBeforeATagStillCounts() {
        yes(
            "What did we decide for the launch, you know?",
            "Quand est-ce qu'on livre, tu sais ?"
        )
    }

    @Test
    fun tooShortIsNotACandidate() {
        no("Really?", "How so?", "What time is?", "Et toi ?", "Qui ça ?", "", "   ")
    }

    @Test
    fun curlyApostrophesAreRead() {
        yes("Qu’est-ce qu’on avait décidé pour le budget", "What’s the plan for the launch")
    }
}
