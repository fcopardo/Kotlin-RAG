package rag

import rag.extract.Normalizer
import rag.extract.TestPdfs
import rag.structure.ChapterStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SPEC §11 generated-PDF scenario: cover, 2 TOC pages, chapters at PDF pages 5, 8 and 11. */
class BookReaderTest {

    private val reader = BookReader()

    private val pages = SampleBook.pages
    private val outline = SampleBook.outline

    @Test
    fun `uses the outline when there is one`() {
        val result = TestPdfs.build(pages, outline).use { PdfBookSource().read(it) }

        assertEquals(ChapterStrategy.OUTLINE, result.plan.strategy)
        assertEquals(setOf(2, 3), result.plan.tocPages)
        assertChapters(result)
    }

    @Test
    fun `uses the TOC pages and finds the page offset without an outline`() {
        val result = TestPdfs.build(pages).use { PdfBookSource().read(it, ReadOptions(tocPages = setOf(2, 3))) }

        assertEquals(ChapterStrategy.TOC, result.plan.strategy)
        assertEquals(4, result.plan.pageOffset)
        assertTrue(result.plan.warnings.isEmpty(), result.plan.warnings.toString())
        assertChapters(result)
    }

    @Test
    fun `falls back to one chapter without outline or TOC pages`() {
        val result = TestPdfs.build(pages).use { PdfBookSource().read(it) }

        assertEquals(ChapterStrategy.FALLBACK, result.plan.strategy)
        assertEquals(listOf("Book"), result.chapters.map { it.title })
        assertEquals(0, result.chapters.single().charStart)
    }

    private fun assertChapters(result: StructuredBook) {
        val chapters = result.chapters
        assertEquals(listOf(0, 1, 2, 3), chapters.map { it.number })
        assertEquals(listOf("Front matter", "Introduction", "Riding Fast", "Repairs"), chapters.map { it.title })
        assertEquals(listOf(1, 5, 8, 11), chapters.map { it.pageStart })
        assertEquals(listOf(4, 7, 10, 12), chapters.map { it.pageEnd })

        val text = result.book.text
        assertFalse("Riding Fast . ." in text, "TOC pages should be left out")
        assertFalse("The Bike Book" in text, "running book header should be removed")
        assertFalse(Regex("(?m)^\\d+$").containsMatchIn(text), "page-number footers should be removed")
        assertTrue("A short preface" in result.text(chapters[0]))

        // Each chapter keeps its heading once; running chapter titles on later pages are gone.
        chapters.drop(1).forEach { c ->
            val chapterText = result.text(c)
            assertTrue(chapterText.startsWith(c.title + "\n"), "chapter ${c.number} should start at its heading")
            val titleLines = chapterText.lines().count { Normalizer.hashNormalize(it) == Normalizer.hashNormalize(c.title) }
            assertEquals(1, titleLines, "chapter ${c.number}:\n$chapterText")
        }
    }

    @Test
    fun `parses toc page ranges`() {
        assertEquals(setOf(5, 6, 7, 8), BookReader.parsePageRanges("5-8"))
        assertEquals(setOf(2, 3, 5), BookReader.parsePageRanges("2-3,5"))
    }
}
