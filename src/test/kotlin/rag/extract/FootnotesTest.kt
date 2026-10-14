package rag.extract

import kotlin.test.Test
import kotlin.test.assertEquals

class FootnotesTest {

    @Test
    fun `removes a URL footnote and its marker`() {
        val page = "Read the book by Joe Birch¹. It is good.\n\n¹https://practicaljetpackcompose.com/\n9"

        assertEquals("Read the book by Joe Birch. It is good.\n\n9", Footnotes.clean(page))
    }

    @Test
    fun `removes a URL that wraps onto the next line`() {
        val page = "Read the tests⁴ first.\n⁴https://cs.android.com/androidx/platform/integration-\ntests/src/Foo.kt\nEnd"

        assertEquals("Read the tests first.\nEnd", Footnotes.clean(page))
    }

    @Test
    fun `keeps a prose footnote without its marker`() {
        val page = "A claim²⁵.\n²⁵ See the appendix for details."

        assertEquals("A claim.\nSee the appendix for details.", Footnotes.clean(page))
    }

    @Test
    fun `keeps superscripts that are not footnotes on the page`() {
        val page = "Sorting is O(n²) here.\n¹https://example.com/\nWith a note¹."

        assertEquals("Sorting is O(n²) here.\nWith a note.", Footnotes.clean(page))
    }

    @Test
    fun `leaves a page without footnotes untouched`() {
        val page = "Plain text with x² and nothing else."

        assertEquals(page, Footnotes.clean(page))
    }

    @Test
    fun `assembled text has no footnote digits glued to words`() {
        val book = PdfExtractor().assemble(listOf("The post by Andrei Shikov⁵.\n⁵https://example.com/post"))

        assertEquals("The post by Andrei Shikov.\n", book.text)
    }
}
