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

    // --- explicit paragraph blocks (code functions, SPEC §18.2) ---

    private fun blockBook(text: String, vararg blocks: String) = blocks.map { b ->
        val start = text.indexOf(b).also { require(it >= 0) { "not in text: $b" } }
        start until start + b.length
    }.let { ranges -> ExtractedBook(text, intArrayOf(0), ranges, blocks = ranges) }

    private fun ExtractedBook.paragraphTexts() = paragraphs().map { text.substring(it.start, it.end + 1) }

    @Test
    fun `an explicit block with a blank line inside is one paragraph of lines`() {
        val fn = "fun foo() {\n    a()\n\n    b()\n}"
        val text = "Intro text.\n\n$fn\n\nAfter."
        val b = blockBook(text, fn)

        assertEquals(listOf("Intro text.", fn, "After."), b.paragraphTexts())
        val code = b.paragraphs()[1]
        assertTrue(code.isCode)
        assertEquals(listOf("fun foo() {", "a()", "b()", "}"), b.phraseTexts(code).map { it.trim() })
        // Without the block, the blank line would cut the function in two.
        assertEquals(4, book(text, b.codeRanges).paragraphs().size)
    }

    @Test
    fun `adjacent explicit blocks stay separate paragraphs`() {
        val a = "fun a() {\n}"
        val c = "fun c() {\n}"
        val text = "$a\n$c"

        assertEquals(listOf(a, c), blockBook(text, a, c).paragraphTexts())
        assertEquals(1, book(text, listOf(text.indices)).paragraphs().size, "without blocks they would merge")
    }

    @Test
    fun `text between explicit blocks is still split at blank lines`() {
        val fn = "fun f() {\n}"
        val text = "One.\n\nTwo.\n$fn\nThree.\n\nFour."

        assertEquals(listOf("One.", "Two.", fn, "Three.", "Four."), blockBook(text, fn).paragraphTexts())
    }

    @Test
    fun `an explicit block over the cap is split at line ends`() {
        val fn = (1..12).joinToString("\n", "fun long() {\n", "\n}") { "    val v$it = compute(alpha, beta, gamma)" }
        val text = "Intro.\n\n$fn"
        val b = blockBook(text, fn)
        val start = text.indexOf(fn)

        val code = Segmenter(maxTokens = 20).paragraphs(b, 0, text.length - 1).filter { it.isCode }
        assertTrue(code.size > 1, "split into several paragraphs")
        assertEquals(start, code.first().start)
        assertEquals(text.length - 1, code.last().end)
        code.forEach { p -> assertTrue(p.end == text.length - 1 || text[p.end + 1] == '\n', "ends at a line end") }
    }

    @Test
    fun `explicit blocks clipped to the range keep every span trimmed and inside it`() {
        val fn = "fun g() {\n    x()\n\n}"
        val text = "Lead.\n\n$fn\n\n  Tail.  "
        val b = blockBook(text, fn)
        val from = text.indexOf("x()")

        val paragraphs = segmenter.paragraphs(b, from, text.length - 1)
        assertEquals(listOf("x()\n\n}", "Tail."), paragraphs.map { text.substring(it.start, it.end + 1) })
        paragraphs.flatMap { listOf(it.start..it.end) + it.phrases.map { ph -> ph.start..ph.end } }.forEach { r ->
            val s = text.substring(r.first, r.last + 1)
            assertEquals(s.trim(), s)
            assertTrue(r.first >= from)
        }
    }
}
