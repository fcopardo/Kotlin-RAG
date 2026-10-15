package rag.index

import rag.StructuredBook
import rag.extract.ExtractedBook
import rag.extract.Normalizer
import rag.structure.Chapter
import rag.structure.ChapterPlan
import rag.structure.ChapterStrategy
import rag.structure.Segmenter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitKeysTest {

    /** Chapter 1: the phrase twice; chapter 2: once (SPEC §11). */
    private val text = "One\n\nThe bike went really fast. The bike went really fast.\nTwo\n\nThe bike went really fast.\n"
    private val split = text.indexOf("Two")

    private fun structured(): StructuredBook {
        val book = ExtractedBook(text, intArrayOf(0))
        val chapters = listOf(Chapter(1, "One", 0, split - 1, 1, 1), Chapter(2, "Two", split, text.length - 1, 1, 1))
        return StructuredBook(book, chapters, ChapterPlan(ChapterStrategy.FALLBACK, emptyList()), Segmenter().segment(book, chapters))
    }

    @Test
    fun `phrase keys count occurrences per chapter`() {
        val h = Normalizer.hash("The bike went really fast.")

        val records = UnitKeys.build(structured(), bookId = 7)

        val keys = records.filter { it.hash == h && it.kind == UnitKind.PHRASE }.map { it.key }
        assertEquals(listOf("$h-0p-1c", "$h-1p-1c", "$h-0p-2c"), keys)
    }

    @Test
    fun `book, chapter and paragraph keys follow the format`() {
        val records = UnitKeys.build(structured(), bookId = 7)

        val book = records.first()
        assertEquals(UnitKind.BOOK, book.kind)
        assertEquals("${book.hash}-0b", book.key)
        val chapters = records.filter { it.kind == UnitKind.CHAPTER }
        assertEquals(listOf("${chapters[0].hash}-0c-7b", "${chapters[1].hash}-0c-7b"), chapters.map { it.key })
        assertEquals(listOf(1, 2), chapters.map { it.ordinal })
        assertTrue(records.filter { it.kind == UnitKind.PARAGRAPH }.all { Regex("^[0-9a-f]{16}-\\d+g-\\d+c$").matches(it.key) })
    }

    @Test
    fun `parents and offsets nest`() {
        val records = UnitKeys.build(structured(), bookId = 1)

        records.forEach { r ->
            val parent = r.parentIndex?.let(records::get) ?: return@forEach
            val expectedParent = when (r.kind) {
                UnitKind.PHRASE -> UnitKind.PARAGRAPH
                UnitKind.PARAGRAPH -> UnitKind.CHAPTER
                UnitKind.CHAPTER -> UnitKind.BOOK
                UnitKind.BOOK -> error("book has no parent")
            }
            assertEquals(expectedParent, parent.kind)
            assertTrue(r.start >= parent.start && r.end <= parent.end)
        }
        assertEquals(records.size, records.map { it.key }.toSet().size, "keys are unique")
    }

    @Test
    fun `keys parse back into their parts`() {
        UnitKeys.build(structured(), bookId = 7).forEach { r ->
            val parsed = UnitKeys.parse(r.key)!!
            assertEquals(r.hash, parsed.hash)
            assertEquals(r.occurrence, parsed.occurrence)
            assertEquals(r.kind, parsed.kind)
            assertEquals(r.key, UnitKeys.format(parsed.hash, parsed.occurrence, parsed.kind, parsed.scope))
        }
    }

    @Test
    fun `malformed keys don't parse`() {
        listOf("", "nothex", "3fa9c0d1e2b47a10-0p", "3fa9c0d1e2b47a10-0p-1b", "3fa9c0d1e2b47a10-0c-1c", "3fa9-0p-1c")
            .forEach { assertEquals(null, UnitKeys.parse(it), it) }
    }
}
