package rag.language

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout
import rag.BookReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** [LanguageDetector.detectDocument] on the real texts the tests have: committed fixtures, and opt-in book PDFs. */
@Timeout(120)
class RealTextLanguageTest {
    private fun languageOf(file: File) = LanguageDetector.detectDocument(BookReader().read(file).book.text)

    @Test
    fun `the committed fixtures are English`() {
        val files = listOf("epub/sun-tzu_the-art-of-war_lionel-giles.epub", "epub/pg132.epub") +
            File("src/test/resources/documents/papers").list()!!.sorted().map { "documents/papers/$it" } +
            File("src/test/resources/documents/contracts").list()!!.sorted().map { "documents/contracts/$it" }
        files.forEach { assertEquals("en", languageOf(File("src/test/resources/$it")), it) }
    }

    @Test
    fun `the Compose book is English`() {
        val pdf = System.getenv("RAG_BOOK_PDF")?.let(::File)
        assumeTrue(pdf?.isFile == true, "needs RAG_BOOK_PDF")
        assertEquals("en", languageOf(pdf!!))
    }

    @Test
    fun `the Spanish book is Spanish`() {
        val pdf = System.getenv("RAG_BOOK_ES_PDF")?.let(::File)
        assumeTrue(pdf?.isFile == true, "needs RAG_BOOK_ES_PDF")
        assertEquals("es", languageOf(pdf!!))
    }
}
