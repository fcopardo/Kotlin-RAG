package rag.extract

import kotlin.test.Test
import kotlin.test.assertEquals

class BoilerplateRemoverTest {

    private val remover = BoilerplateRemover()

    @Test
    fun `removes running header and numbered footer`() {
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        val pages = words.mapIndexed { i, w -> "The Running Title\nBody $w.\nMore $w.\n${i + 1}" }

        val cleaned = remover.remove(pages)

        cleaned.forEachIndexed { i, page ->
            assertEquals("Body ${words[i]}.\nMore ${words[i]}.", page)
        }
    }

    @Test
    fun `keeps repeated lines that are not at the page edges`() {
        val pages = (1..6).map { "Header $it-x\nFirst.\nRepeated middle line\nLast.\nFooter $it-y" }

        val cleaned = remover.remove(pages)

        cleaned.forEach { assert("Repeated middle line" in it) }
    }

    @Test
    fun `keeps edge lines of short documents`() {
        // "Shared" is on 2 of 3 pages: over 30%, but under the 3-page floor.
        val pages = listOf("Shared\nAlpha.", "Shared\nBravo.", "Other\nCharlie.")

        assertEquals(pages, remover.remove(pages))
    }

    @Test
    fun `removes running chapter titles but keeps the chapter heading`() {
        val pages = listOf(
            "1. Composable functions\nOpening text.",
            "1. Composable functions 4\nMore text.",
            "5 1. Composable functions\nEven more.",
            "2. The Compose compiler\nNew chapter.",
            "2. The Compose compiler 18\nCompiler text.",
        )
        val starts = listOf("1. Composable functions" to 1, "2. The Compose compiler" to 4)

        val cleaned = remover.removeRunningTitles(pages, starts)

        assertEquals(
            listOf("1. Composable functions\nOpening text.", "More text.", "Even more.", "2. The Compose compiler\nNew chapter.", "Compiler text."),
            cleaned,
        )
    }

    @Test
    fun `only removes the current chapter's title`() {
        val pages = listOf("Alpha\nText.", "Beta\nMentions beta at the top.")

        // Page 2 belongs to chapter Alpha, so a "Beta" line there stays.
        assertEquals(pages, remover.removeRunningTitles(pages, listOf("Alpha" to 1)))
    }

    @Test
    fun `keeps lines below the page fraction`() {
        // Repeated on 3 of 12 pages (25%): under the 30% threshold.
        val pages = (1..12).map { if (it <= 3) "Same top\nBody $it.\nEnd $it a" else "Top $it x\nBody $it.\nEnd $it b" }

        val cleaned = remover.remove(pages)

        assert(cleaned.take(3).all { it.startsWith("Same top") })
    }
}
