package rag.structure

import rag.extract.ExtractedBook
import kotlin.test.Test
import kotlin.test.assertEquals

class ChaptersTest {

    /** Book text from page texts, each page ending with a newline like the extractor builds it. */
    private fun book(vararg pages: String): ExtractedBook {
        val starts = IntArray(pages.size)
        val text = StringBuilder()
        pages.forEachIndexed { i, p ->
            starts[i] = text.length
            if (p.isNotEmpty()) text.append(p).append('\n')
        }
        return ExtractedBook(text.toString(), starts)
    }

    private fun ExtractedBook.slice(c: Chapter) = text.substring(c.charStart, c.charEnd + 1)

    @Test
    fun `chapters start at their title line, with front matter before`() {
        val b = book("Cover page.", "Some text first.\nOne\nChapter one body.", "More of one.", "Two\nChapter two body.")

        val chapters = Chapters.compute(b, listOf(ChapterStart("One", 2), ChapterStart("Two", 4)))

        assertEquals(listOf(0, 1, 2), chapters.map { it.number })
        assertEquals("Cover page.\nSome text first.\n", b.slice(chapters[0]))
        assertEquals("One\nChapter one body.\nMore of one.\n", b.slice(chapters[1]))
        assertEquals("Two\nChapter two body.\n", b.slice(chapters[2]))
        assertEquals(listOf(1 to 2, 2 to 3, 4 to 4), chapters.map { it.pageStart to it.pageEnd })
    }

    @Test
    fun `chapters cover the whole text without gaps`() {
        val b = book("Front.", "One\nx", "Two\ny", "z")

        val chapters = Chapters.compute(b, listOf(ChapterStart("One", 2), ChapterStart("Two", 3)))

        assertEquals(0, chapters.first().charStart)
        assertEquals(b.text.length - 1, chapters.last().charEnd)
        chapters.zipWithNext().forEach { (a, c) -> assertEquals(a.charEnd + 1, c.charStart) }
    }

    @Test
    fun `falls back to the page top when the title line is missing`() {
        val b = book("Front.", "Body without the heading.")

        val chapters = Chapters.compute(b, listOf(ChapterStart("Lost title", 2)))

        assertEquals("Body without the heading.\n", b.slice(chapters[1]))
    }

    @Test
    fun `no front matter when the first chapter starts at the top`() {
        val b = book("Book\nAll text.")

        val chapters = Chapters.compute(b, listOf(ChapterStart("Book", 1)))

        assertEquals(listOf(1), chapters.map { it.number })
    }

    @Test
    fun `chapter on an empty page starts at the next text`() {
        // Page 2 is empty (e.g. a skipped TOC page); chapter One's title is on page 3.
        val b = book("Front.", "", "One\nBody.")

        val chapters = Chapters.compute(b, listOf(ChapterStart("One", 3)))

        assertEquals("One\nBody.\n", b.slice(chapters[1]))
    }
}
