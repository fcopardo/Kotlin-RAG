package rag.structure

import kotlin.test.Test
import kotlin.test.assertEquals

class TocParserTest {

    private fun lines(vararg text: String) = text.map { TocLine(it, 72f) }

    @Test
    fun `parses spec acceptance lines and skips roman pages`() {
        val entries = TocParser.parse(lines("Introduction ........ 1", "Chapter 2 · The Road   17", "Preface ... ix"))

        assertEquals(listOf(TocEntry("Introduction", 1), TocEntry("Chapter 2 · The Road", 17)), entries)
    }

    @Test
    fun `parses spaced dot leaders and numbered titles`() {
        val entries = TocParser.parse(lines("Contents", "Prelude . . . . . . 1", "1. Composable functions . . . . 3"))

        assertEquals(listOf(TocEntry("Prelude", 1), TocEntry("1. Composable functions", 3)), entries)
    }

    @Test
    fun `keeps only the least indented entries`() {
        val entries = TocParser.parse(
            listOf(
                TocLine("1. Composable functions . . . 3", 72f),
                TocLine("The meaning of Composable functions . . . 3", 90.5f),
                TocLine("2. The Compose compiler . . . 17", 72.4f),
                TocLine("A Kotlin compiler plugin . . . 17", 90.5f),
            )
        )

        assertEquals(listOf("1. Composable functions", "2. The Compose compiler"), entries.map { it.title })
    }

    @Test
    fun `OCR bullets in leaders still parse, and leaders without a number still look like a TOC`() {
        assertEquals(listOf(TocEntry("Chapter 16 j Moderate", 181)), TocParser.parse(lines("Chapter 16 j Moderate .........·.......•....181")))
        assertEquals(true, TocParser.looksLikeToc("Additional Review Problems . . • . . . . . .\nChapter 17 j Hard ........ 186\nMore . . . . . . ."))
        assertEquals(false, TocParser.looksLikeToc("Plain prose line.\nAnother line.\nWait... what?"))
    }

    @Test
    fun `ignores lines that are not entries`() {
        assertEquals(emptyList(), TocParser.parse(lines("CONTENTS", "Some running text without a page")))
    }
}
