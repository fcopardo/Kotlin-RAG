package rag.document

import org.junit.jupiter.api.io.TempDir
import rag.RagException
import rag.structure.ChapterStrategy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextBookSourceTest {
    @TempDir
    lateinit var dir: File

    private fun read(name: String, body: String) = TextBookSource().read(File(dir, name).apply { writeText(body) })

    @Test
    fun `markdown headings become sections with a path title`() {
        val book = read(
            "guide.md",
            """
            # The Bike Book

            An introduction to bicycles and how they work.

            ## Gears

            Gears let riders climb steep hills with less effort.

            ### Derailleurs

            A derailleur moves the chain between sprockets.

            ## Brakes

            Rim brakes press pads against the wheel.
            """.trimIndent(),
        )

        assertEquals("The Bike Book", book.title)
        assertEquals(ChapterStrategy.MARKDOWN, book.plan.strategy)
        assertEquals("paper", book.kind)
        assertEquals(
            listOf(
                "The Bike Book",
                "The Bike Book › Gears",
                "The Bike Book › Gears › Derailleurs",
                "The Bike Book › Brakes",
            ),
            book.chapters.filter { it.number > 0 }.map { it.title },
        )
        // The heading line is part of the text, so its section starts at it.
        assertTrue(book.text(book.chapters.last()).startsWith("Brakes"), book.text(book.chapters.last()).take(40))
    }

    @Test
    fun `setext headings and fenced code are recognized`() {
        val book = read(
            "notes.md",
            """
            Release notes
            =============

            The parser now understands fenced code.

            ```kotlin
            fun main() {
                println("hi")
            }
            ```

            That block is stored as code.
            """.trimIndent(),
        )

        assertEquals("Release notes", book.title)
        val code = book.segments.flatMap { it.paragraphs }.filter { it.isCode }
        assertEquals(1, code.size, "one code paragraph")
        assertTrue(book.text(code.single().start, code.single().end).contains("println(\"hi\")"))
        // Code keeps its indentation, and its phrases are lines rather than sentences.
        assertTrue(code.single().phrases.size >= 3, "a line per phrase")
    }

    @Test
    fun `a document without headings is divided by topic`() {
        // Two clearly different topics, four paragraphs each.
        val bikes = (1..4).joinToString("\n\n") { "Bicycle wheels and bicycle gears need regular cleaning, number $it. Riders oil the bicycle chain often." }
        val soup = (1..4).joinToString("\n\n") { "Tomato soup simmers with onion and garlic, recipe $it. The soup tastes better with fresh bread." }

        val book = read("mixed.txt", "$bikes\n\n$soup")

        assertEquals(ChapterStrategy.IDEAS, book.plan.strategy)
        assertEquals(2, book.chapters.count { it.number > 0 }, book.chapters.map { it.title }.toString())
        val titles = book.chapters.filter { it.number > 0 }.map { it.title }
        assertTrue(titles[0].startsWith("Bicycle wheels"), titles.toString())
        assertTrue(titles[1].startsWith("Tomato soup"), titles.toString())
    }

    @Test
    fun `a short flat document stays one section`() {
        val book = read("short.txt", "One short note about nothing in particular.\n\nAnd a second line.")

        assertEquals(ChapterStrategy.FALLBACK, book.plan.strategy)
        assertEquals(1, book.chapters.count { it.number > 0 })
    }

    @Test
    fun `an empty document is rejected`() {
        assertFailsWith<RagException.BookUnreadable> { read("empty.txt", "   \n\n  ") }
    }
}
