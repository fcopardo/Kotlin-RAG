package rag.structure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TocLocatorTest {

    private val locator = TocLocator()

    /** Builds 1-based pages: the map gives page -> text, everything else is filler. */
    private fun pages(count: Int, content: Map<Int, String>) = (1..count).map { content[it] ?: "Filler text page." }

    @Test
    fun `finds offset from the first title after the TOC`() {
        val pages = pages(12, mapOf(2 to "Introduction . . 1", 5 to "Introduction\nBody.", 8 to "Riding Fast\nBody.", 11 to "Repairs\nBody."))
        val entries = listOf(TocEntry("Introduction", 1), TocEntry("Riding Fast", 4), TocEntry("Repairs", 7))

        val located = locator.locate(entries, pages, searchFrom = 3)!!

        assertEquals(4, located.pageOffset)
        assertEquals(listOf(5, 8, 11), located.starts.map { it.pdfPage })
        assertTrue(located.warnings.isEmpty())
    }

    @Test
    fun `carries drift forward when a chapter is found off by some pages`() {
        // An unnumbered insert before chapter 2 pushes everything after it one page later.
        val pages = pages(14, mapOf(5 to "Intro", 9 to "Second", 12 to "Third"))
        val entries = listOf(TocEntry("Intro", 1), TocEntry("Second", 4), TocEntry("Third", 7))

        val located = locator.locate(entries, pages, searchFrom = 3)!!

        assertEquals(listOf(5, 9, 12), located.starts.map { it.pdfPage })
    }

    @Test
    fun `keeps the computed page and warns when a title is missing`() {
        val pages = pages(12, mapOf(5 to "Intro", 11 to "Third"))
        val entries = listOf(TocEntry("Intro", 1), TocEntry("Missing", 4), TocEntry("Third", 7))

        val located = locator.locate(entries, pages, searchFrom = 3)!!

        assertEquals(listOf(5, 8, 11), located.starts.map { it.pdfPage })
        assertEquals(1, located.warnings.size)
    }

    @Test
    fun `matches a title wrapped over two lines`() {
        val pages = pages(8, mapOf(4 to "7. Advanced Compose\nRuntime use cases\nBody."))

        val located = locator.locate(listOf(TocEntry("7. Advanced Compose Runtime use cases", 1)), pages, searchFrom = 2)

        assertEquals(4, located!!.starts.single().pdfPage)
    }

    @Test
    fun `ignores the title inside a running header with a page number`() {
        val pages = pages(8, mapOf(3 to "Intro 2\nBody.", 5 to "Intro\nBody."))

        val located = locator.locate(listOf(TocEntry("Intro", 1)), pages, searchFrom = 2)

        assertEquals(5, located!!.starts.single().pdfPage)
    }

    @Test
    fun `returns null when the first title is nowhere after the TOC`() {
        assertNull(locator.locate(listOf(TocEntry("Nowhere", 1)), pages(5, emptyMap()), searchFrom = 2))
    }
}
