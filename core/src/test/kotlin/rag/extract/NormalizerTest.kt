package rag.extract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NormalizerTest {

    @Test
    fun `rejoins words hyphenated at line end`() {
        assertEquals("example", Normalizer.normalize("exam-\nple"))
        assertEquals("an example here", Normalizer.normalize("an exam-\n  ple here"))
    }

    @Test
    fun `keeps hyphen when either side is not lowercase`() {
        assertEquals("well-\nKnown", Normalizer.normalize("well-\nKnown"))
        assertEquals("COVID-\n19", Normalizer.normalize("COVID-\n19"))
        assertEquals("Anglo-\nSaxon", Normalizer.normalize("Anglo-\nSaxon"))
    }

    @Test
    fun `soft hyphens are removed and rejoin words at line ends`() {
        assertEquals("implement this", Normalizer.normalize("imple\u00AD\nment this"))
        assertEquals("permutation", Normalizer.normalize("permu\u00ADtation"))
    }

    @Test
    fun `zero-width characters are removed`() {
        assertEquals("Endnotes 1–500", Normalizer.normalize("Endnotes 1\u2060–\u2060500\u200B\uFEFF"))
    }

    @Test
    fun `expands ligatures with NFKC`() {
        assertEquals("first offer", Normalizer.normalize("ﬁrst oﬀer"))
    }

    @Test
    fun `normalizes line endings and whitespace`() {
        assertEquals("a b\nc\n\nd", Normalizer.normalize("a \t b  \r\nc\r\n\r\n\r\n\r\nd   "))
    }

    @Test
    fun `turns non-breaking spaces into spaces`() {
        assertEquals("a b", Normalizer.normalize("a b"))
    }

    @Test
    fun `hash ignores case and whitespace differences`() {
        val a = Normalizer.hash("The bike went\nreally fast.")
        val b = Normalizer.hash("  the BIKE   went really fast. ")
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertNotEquals(a, Normalizer.hash("The bike went really slow."))
    }
}
