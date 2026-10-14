package rag.summarize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryTextTest {

    @Test
    fun `preambles are stripped`() {
        assertEquals("The chapter covers snapshots.", SummaryText.clean("Here is a summary of the passage:\nThe chapter covers snapshots.", 50))
        assertEquals("The chapter covers snapshots.", SummaryText.clean("Summary: The chapter covers snapshots.", 50))
        assertEquals("The chapter covers snapshots.", SummaryText.clean("Sure! Here you go.\nThe chapter covers snapshots.", 50))
    }

    @Test
    fun `long output is cut at the last sentence end within the cap`() {
        val sentence = (1..9).joinToString(" ") { "word" } + " end." // 10 words
        val raw = List(30) { sentence }.joinToString(" ")                // 300 words

        val cleaned = SummaryText.clean(raw, 120)

        assertEquals(120, cleaned.split(" ").size)
        assertTrue(cleaned.endsWith("end."))
    }

    @Test
    fun `without a sentence end the text is cut at the cap`() {
        assertEquals("a b c", SummaryText.clean("a b c d e", 3))
    }

    @Test
    fun `short text is only trimmed`() {
        assertEquals("Fine as is.", SummaryText.clean("  Fine as is.  ", 10))
    }
}
