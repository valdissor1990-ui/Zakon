package ru.zakon.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalReferenceParserTest {
    @Test
    fun parsesArticleReferenceWithAbbreviation() {
        val parsed = LegalReferenceParser.parse("ст. 159 УК РФ")
        assertEquals("159", parsed?.article)
        assertTrue(parsed?.allowSubArticles == true)
    }

    @Test
    fun resolvesCriminalCodeHint() {
        assertEquals("уголовный кодекс", LegalReferenceParser.parse("ст. 159 УК РФ")?.documentHint)
    }

    @Test
    fun parsesDecimalArticleReference() {
        val parsed = LegalReferenceParser.parse("статья 12.1")
        assertEquals("12.1", parsed?.article)
        assertFalse(parsed?.allowSubArticles ?: true)
    }

    @Test
    fun doesNotTreatAnUnqualifiedNumberAsLegalReference() {
        assertEquals(null, LegalReferenceParser.parse("найти 159"))
    }

    @Test
    fun normalizesRussianYoAndPunctuation() {
        assertEquals("ежик закон", LegalReferenceParser.normalize("Ёжик, ЗАКОН!"))
    }

    @Test
    fun removesLegalStopWordsFromFullTextTokens() {
        assertEquals(listOf("159", "ук"), LegalReferenceParser.tokens("ст. 159 УК РФ"))
    }
}
