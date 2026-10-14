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

    @Test
    fun `footers differing only in OCR noise are one footer`() {
        val seps = listOf("I", "\\", "J", "/", "j", "I")
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        val pages = words.mapIndexed { i, w -> "Body $w.\nMore $w.\nCrackingTheCodingInterview.com ${seps[i]} 6th Edition ${190 + i}" }

        remover.remove(pages).forEachIndexed { i, page -> assertEquals("Body ${words[i]}.\nMore ${words[i]}.", page) }
    }

    @Test
    fun `a closing brace at page edges is never boilerplate`() {
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        val pages = words.map { "fun $it() {\nreturn $it\n}" }

        assertEquals(pages, remover.remove(pages))
    }

    @Test
    fun `running chapter headers repeated within a chapter are removed`() {
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf")
        // Chapter "1. Solutions to Arrays and Strings" starts on page 1; its header has other words.
        val pages = words.mapIndexed { i, w ->
            if (i == 0) "1. Solutions to Arrays and Strings\nOpening $w." else "Solutions to Chapter 1 | Arrays and Strings\nText $w.\nMore $w."
        }

        val cleaned = remover.removeRunningTitles(pages, listOf("1. Solutions to Arrays and Strings" to 1))

        assertEquals(pages[0], cleaned[0])
        cleaned.drop(1).forEachIndexed { i, page -> assertEquals("Text ${words[i + 1]}.\nMore ${words[i + 1]}.", page) }
    }

    @Test
    fun `a running title keeps matching when the chapter number starts the line`() {
        val pages = listOf("5 Writing your first class\nIntro.", "Body text.\n5 Writing your first class 26")

        assertEquals(listOf(pages[0], "Body text."), remover.removeRunningTitles(pages, listOf("5 Writing your first class" to 1)))
    }

    @Test
    fun `a chapter heading survives when its running headers are book-wide boilerplate`() {
        // The chapter spans most of the book, so its header is on > 30% of pages.
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")
        val pages = listOf("1. Big Chapter\nOpening.") + words.map { "1. Big Chapter 7\nText $it.\nMore $it." }

        val cleaned = remover.remove(pages, chapterStarts = listOf("1. Big Chapter" to 1))

        assertEquals("1. Big Chapter\nOpening.", cleaned[0])
        assertEquals("Text alpha.\nMore alpha.", cleaned[1])
    }
}
