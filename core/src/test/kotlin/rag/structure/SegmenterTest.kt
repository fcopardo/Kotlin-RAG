package rag.structure

import rag.extract.ExtractedBook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmenterTest {

    private val segmenter = Segmenter()

    private fun book(text: String, code: List<IntRange> = emptyList()) = ExtractedBook(text, intArrayOf(0), code)

    private fun ExtractedBook.paragraphs() = segmenter.paragraphs(this, 0, text.length - 1)

    private fun ExtractedBook.phraseTexts(p: ParagraphSpan) = p.phrases.map { text.substring(it.start, it.end + 1) }

    @Test
    fun `spec acceptance - abbreviations and decimals don't split sentences`() {
        val b = book("Dr. Smith paid \$3.14 for it. Was it worth it? Yes!")

        assertEquals(listOf("Dr. Smith paid \$3.14 for it.", "Was it worth it?", "Yes!"), b.phraseTexts(b.paragraphs().single()))
    }

    @Test
    fun `paragraphs end at blank lines and wrapped lines continue them`() {
        val b = book("Chapter 1 · The Ride\n\nThe bike went really fast. Dr. Lee\nwatched from the hill.\n\nIt was over in a minute.\n")

        val paragraphs = b.paragraphs()

        assertEquals(
            listOf("Chapter 1 · The Ride", "The bike went really fast. Dr. Lee\nwatched from the hill.", "It was over in a minute."),
            paragraphs.map { b.text.substring(it.start, it.end + 1) },
        )
        assertEquals(listOf("The bike went really fast.", "Dr. Lee\nwatched from the hill."), b.phraseTexts(paragraphs[1]))
    }

    @Test
    fun `initials and code-like dots stay in one sentence`() {
        val b = book("Written by J. Castillo for the book. Call Modifier.padding(16.dp) first.")

        assertEquals(
            listOf("Written by J. Castillo for the book.", "Call Modifier.padding(16.dp) first."),
            b.phraseTexts(b.paragraphs().single()),
        )
    }

    @Test
    fun `a closing quote stays with its sentence`() {
        val b = book("He said “stop.” Then he left.")

        assertEquals(listOf("He said “stop.”", "Then he left."), b.phraseTexts(b.paragraphs().single()))
    }

    @Test
    fun `text without a terminator is one phrase`() {
        val b = book("Free of uncontrolled side effects")

        assertEquals(1, b.paragraphs().single().phrases.size)
    }

    @Test
    fun `code paragraphs have one phrase per line`() {
        val text = "Intro text:\n\n@Composable\nfun Foo() {\n}\n\nAfter."
        val codeStart = text.indexOf("@Composable")
        val b = book(text, listOf(codeStart..text.indexOf("}")))

        val paragraphs = b.paragraphs()

        assertEquals(listOf(false, true, false), paragraphs.map { it.isCode })
        assertEquals(listOf("@Composable", "fun Foo() {", "}"), b.phraseTexts(paragraphs[1]))
    }

    @Test
    fun `long paragraphs are split between sentences under the cap`() {
        val sentence = "Word " + (2..20).joinToString(" ") { "word" } + "." // 20 words = 26 tokens; capitalized so BreakIterator splits
        val b = book(List(40) { sentence }.joinToString(" "))      // 1040 tokens

        val paragraphs = b.paragraphs()

        assertEquals(3, paragraphs.size)
        paragraphs.forEach { p ->
            val words = b.text.substring(p.start, p.end + 1).split(' ').size
            assertTrue(words * 1.3 <= 400, "paragraph has $words words")
        }
        assertEquals(40, paragraphs.sumOf { it.phrases.size })
    }

    @Test
    fun `a single sentence over the cap is split between words`() {
        val b = book((1..700).joinToString(" ") { "w$it" } + ".")

        val paragraphs = b.paragraphs()

        assertEquals(3, paragraphs.size) // 307 + 307 + 86 words
        assertEquals(b.text.length - 1, paragraphs.last().end)
    }

    @Test
    fun `segments chapters separately and never crosses them`() {
        val text = "One\n\nFirst chapter text.\nTwo\n\nSecond chapter text.\n"
        val b = book(text)
        val split = text.indexOf("Two")
        val chapters = listOf(Chapter(1, "One", 0, split - 1, 1, 1), Chapter(2, "Two", split, text.length - 1, 1, 1))

        val segmented = segmenter.segment(b, chapters)

        assertEquals(listOf(2, 2), segmented.map { it.paragraphs.size })
        assertEquals("First chapter text.", segmented[0].paragraphs[1].let { text.substring(it.start, it.end + 1) })
    }

    @Test
    fun `every span is trimmed and nested in its paragraph`() {
        val b = book("  Lead space. Next one!  \n\n\tTabbed text?\n")

        b.paragraphs().forEach { p ->
            val pText = b.text.substring(p.start, p.end + 1)
            assertEquals(pText.trim(), pText)
            p.phrases.forEach { ph ->
                assertTrue(ph.start >= p.start && ph.end <= p.end)
                val phText = b.text.substring(ph.start, ph.end + 1)
                assertEquals(phText.trim(), phText)
            }
        }
    }
}
