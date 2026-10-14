package rag.extract

import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.junit.jupiter.api.io.TempDir
import rag.RagException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfExtractorTest {

    private val extractor = PdfExtractor()

    // Body lines differ by word, not digit, so the boilerplate rule (digits -> #) can't match them.
    private val names = listOf("one", "two", "three", "four", "five", "six")

    private fun bookPages(count: Int) = (1..count).map { n ->
        listOf(
            "A Book About Bikes",                         // running header
            "",
            "Page ${names[n - 1]} begins here. The bike went fast.",
            "",
            "Second paragraph on page ${names[n - 1]}.",
            "",
            "$n",                                         // page-number footer
        )
    }

    @Test
    fun `extracts text without running headers and footers`() {
        val book = TestPdfs.build(bookPages(6)).use(extractor::extract)

        assertFalse("A Book About Bikes" in book.text)
        assertFalse(Regex("(?m)^\\d+$").containsMatchIn(book.text), "page numbers left in:\n${book.text}")
        (1..6).forEach { assertTrue("Page ${names[it - 1]} begins here." in book.text) }
    }

    @Test
    fun `records where each page starts`() {
        val book = TestPdfs.build(bookPages(6)).use(extractor::extract)

        assertEquals(6, book.pageCount)
        (1..6).forEach { n ->
            val offset = book.text.indexOf("Page ${names[n - 1]} begins here.")
            assertEquals(book.pageStarts[n - 1], offset)
            assertEquals(n, book.pageOf(offset))
            assertEquals(n, book.pageOf(book.text.indexOf("Second paragraph on page ${names[n - 1]}.")))
        }
    }

    @Test
    fun `separates paragraphs with a blank line`() {
        val book = TestPdfs.build(bookPages(6)).use(extractor::extract)

        assertTrue("The bike went fast.\n\nSecond paragraph on page one." in book.text, book.text)
    }

    @Test
    fun `empty pages share the next page's start offset`() {
        val pages = bookPages(4).toMutableList().apply { add(2, emptyList()) }
        val book = TestPdfs.build(pages).use(extractor::extract)

        assertEquals(book.pageStarts[2], book.pageStarts[3])
        assertEquals(4, book.pageOf(book.text.indexOf("Page three begins here.")))
    }

    @Test
    fun `a page break continues the paragraph mid-sentence`() {
        val book = extractor.assemble(listOf("The bike went really", "fast down the hill."))

        assertEquals("The bike went really\nfast down the hill.\n", book.text)
    }

    @Test
    fun `a page break after a finished sentence starts a new paragraph`() {
        val book = extractor.assemble(listOf("The bike went fast.", "Then it stopped."))

        assertEquals("The bike went fast.\n\nThen it stopped.\n", book.text)
        assertEquals(book.text.indexOf("Then"), book.pageStarts[1])
    }

    @Test
    fun `a listing split over two pages stays one code block`() {
        val book = extractor.assemble(listOf("Example:\n\n1 a()\n\n2 b()", "3 c()\n\n4 d()\n\nAfter the listing."))

        val code = book.codeRanges.map { book.text.substring(it.first, it.last + 1) }
        assertEquals(listOf("a()\nb()\nc()\nd()"), code)
        assertTrue("d()\n\nAfter the listing." in book.text)
    }

    @Test
    fun `rejects a PDF without a text layer`() {
        TestPdfs.build(listOf(emptyList(), emptyList())).use { pdf ->
            assertFailsWith<RagException.NoTextLayer> { extractor.extract(pdf) }
        }
    }

    @Test
    fun `rejects a PDF that needs a user password`(@TempDir dir: File) {
        val file = File(dir, "locked.pdf")
        TestPdfs.build(bookPages(1)).use { pdf ->
            pdf.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            pdf.save(file)
        }

        val error = assertFailsWith<RagException.PdfUnreadable> { extractor.extract(file) }
        assertEquals(3, error.exitCode)
    }

    @Test
    fun `accepts a PDF encrypted with only an owner password`(@TempDir dir: File) {
        val file = File(dir, "owner-only.pdf")
        TestPdfs.build(bookPages(3)).use { pdf ->
            pdf.protect(StandardProtectionPolicy("owner", "", AccessPermission()).apply { encryptionKeyLength = 128 })
            pdf.save(file)
        }

        assertTrue("Page one begins here." in extractor.extract(file).text)
    }

    @Test
    fun `rejects a file that is not a PDF`(@TempDir dir: File) {
        val file = File(dir, "nope.pdf").apply { writeText("not a pdf") }

        assertFailsWith<RagException.PdfUnreadable> { extractor.extract(file) }
    }
}
