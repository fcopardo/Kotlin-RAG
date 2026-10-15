package rag.summarize

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummaryTreeTest {

    private fun para(i: Int, words: Int, code: Boolean = false) =
        TreeParagraph(if (code) (1..words).joinToString("\n") { "line$it()" } else (1..words).joinToString(" ") { "w$i" }, code, i * 1000, i * 1000 + 999, i, i)

    @Test
    fun `sections pack paragraphs up to 1500 tokens`() {
        val paragraphs = (0 until 20).map { para(it, 200) } // 260 tokens each

        val sections = SummaryTree.sections(paragraphs)

        assertTrue(sections.all { SummaryTree.tokens(it.text) <= SummaryTree.SECTION_TOKENS })
        assertEquals(4, sections.size) // 5 paragraphs (1300 tokens) per section
        assertEquals(0, sections.first().start)
        assertEquals(19 * 1000 + 999, sections.last().end)
    }

    @Test
    fun `a code paragraph contributes at most 15 lines`() {
        val text = SummaryTree.inputText(para(1, 40, code = true))

        assertEquals(16, text.lines().size)
        assertEquals("…", text.lines().last())
    }

    @Test
    fun `thirty 150-word summaries need one group level of three groups`() {
        val summaries = List(30) { (1..150).joinToString(" ") { "w" } }

        assertTrue(SummaryTree.needsReduction(summaries))
        assertEquals(listOf(12, 12, 6), SummaryTree.groups(30).map { it.size })
        assertTrue(!SummaryTree.needsReduction(List(3) { (1..150).joinToString(" ") { "w" } }))
    }
}
