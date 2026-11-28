package rag.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [KeywordAnalysis]: which tables a language uses, and how text and queries are prepared (SPEC §19.4). */
class KeywordAnalysisTest {
    @Test
    fun `English, undetermined and code keep the English tables`() {
        assertTrue(KeywordAnalysis.usesEnglishTables("en"))
        assertTrue(KeywordAnalysis.usesEnglishTables("und"))
        assertTrue(KeywordAnalysis.usesEnglishTables("zxx"))
        listOf("es", "pt", "fr", "zh").forEach { assertFalse(KeywordAnalysis.usesEnglishTables(it), it) }
    }

    @Test
    fun `Spanish text keeps each word, lower-cased, followed by its stem when it differs`() {
        val text = KeywordAnalysis.indexText("Las Bibliotecas guardaban 3 libros", "es")
        assertEquals(listOf("las", "bibliotecas", "bibliotec", "guardaban", "guard", "3", "libros", "libr"), text.split(" "))
        assertEquals("bibliotec", SpanishStemmer.stem("bibliotecas"))
        assertEquals(SpanishStemmer.stem("biblioteca"), SpanishStemmer.stem("bibliotecas"))
    }

    @Test
    fun `languages without a stemmer keep their words as written`() {
        assertEquals("a biblioteca guarda os livros", KeywordAnalysis.indexText("A Biblioteca guarda os livros.", "pt"))
    }

    @Test
    fun `a Spanish query drops common words and stems the rest`() {
        val terms = KeywordAnalysis.terms("¿Dónde están las bibliotecas de la ciudad?", "es")
        assertEquals(listOf(SpanishStemmer.stem("dónde"), SpanishStemmer.stem("bibliotecas"), SpanishStemmer.stem("ciudad")), terms)
        assertEquals(terms.joinToString(" OR ") { "\"$it\"" }, KeywordAnalysis.anyTerm("¿Dónde están las bibliotecas de la ciudad?", "es"))
    }

    @Test
    fun `a query of only common words keeps them, and an empty one has no terms`() {
        assertEquals(listOf("el", "de"), KeywordAnalysis.terms("el de", "es"))
        assertNull(KeywordAnalysis.anyTerm("¿?", "es"))
    }
}
