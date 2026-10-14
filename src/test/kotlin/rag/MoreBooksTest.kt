package rag

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import rag.structure.ChapterStrategy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structure checks on two more local books, which stay out of the repo:
 * - RAG_KOTLIN_PDF: "Kotlin for Android Developers" (Leanpub): numbered sections under each chapter.
 * - RAG_CTCI_PDF: "Cracking the Coding Interview", 6th edition: parts in the outline, OCR text layer.
 */
@Tag("book")
class MoreBooksTest {

    private fun book(env: String): StructuredBook {
        val pdf = System.getenv(env)?.let(::File)
        assumeTrue(pdf?.isFile == true, "set $env to run")
        return BookReader().read(pdf!!)
    }

    private fun lines(b: StructuredBook, pattern: String) = b.book.text.lines().count { Regex(pattern).matches(it) }

    @Test
    fun `kotlin for android - sections stay inside their chapters, running footers are gone`() {
        val b = book("RAG_KOTLIN_PDF")

        assertEquals(ChapterStrategy.OUTLINE, b.plan.strategy)
        assertEquals(35, b.chapters.size)
        assertEquals("31 Conclusion", b.chapters.last().title)
        // Footers were "<chapter number> <title> <page>", e.g. "5 Writing your first class 26".
        val titles = b.chapters.map { it.title }.toSet()
        val footers = b.book.text.lines().count { line -> titles.any { t -> line.startsWith("$t ") && line.removePrefix("$t ").all(Char::isDigit) } }
        assertEquals(0, footers)
    }

    @Test
    fun `cracking the coding interview - parts give chapters, OCR footers and headers are gone`() {
        val b = book("RAG_CTCI_PDF")

        assertEquals("Cracking the Coding Interview 6th Edition", b.title) // no metadata title: file name
        assertEquals(51, b.chapters.size)
        val titles = b.chapters.map { it.title }
        assertTrue("IX. Interview Questions › 1. Arrays and Strings" in titles)
        assertTrue("X. Solutions › 17. Solutions to Hard" in titles)
        assertTrue("XIII. Hints › I. Hints for Data Structures" in titles)
        assertTrue(titles.none { it == "Introduction" || it == "X. Solutions" }, "parts without own text don't stay chapters")

        // Before the fixes: 385 footers and 470 running headers left; now only OCR-mangled strays.
        assertTrue(lines(b, "(?i)crackingthecoding.nterview\\.com.{1,4}\\d+th edition.*") <= 10)
        assertTrue(lines(b, "(Solutions to )?Chapter \\d+ [I|l\\\\/Jj] .*") <= 5)
        assertFalse('­' in b.book.text, "soft hyphens rejoined")
        assertFalse(Regex("[A-Z][a-z]+ \\. \\. \\. \\. \\. .* \\d+$", RegexOption.MULTILINE).containsMatchIn(b.book.text), "TOC pages left out")
    }
}
