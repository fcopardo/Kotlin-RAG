package rag.epub

import rag.BookReader
import rag.PageKind
import rag.structure.ChapterStrategy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The committed public-domain fixtures (src/test/resources/epub, see README there): the same
 * translation of The Art of War as EPUB 3 (Standard Ebooks) and EPUB 2 (Project Gutenberg #132).
 */
class EpubFixturesTest {

    private val se = File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")
    private val gutenberg = File("src/test/resources/epub/pg132.epub")

    @Test
    fun `standard ebooks - nav chapters, the 13 chapters under their part, endnotes as prose`() {
        val b = BookReader().read(se)

        assertEquals(ChapterStrategy.NAV, b.plan.strategy)
        assertEquals(PageKind.LOCATION, b.pageKind)
        assertEquals("The Art of War", b.title)
        val titles = b.chapters.map { it.title }
        assertEquals(27, titles.size)
        assertEquals(13, titles.count { it.startsWith("The Art of War › ") })
        assertEquals("The Art of War › XIII: The Use of Spies", titles[22])
        assertTrue("Endnotes 1–500" in titles, "zero-width joiners removed from titles")
        assertTrue(b.text(b.chapters[10]).startsWith("I\n\nLaying Plans\n\nSun Tzǔ said: The art of war is of vital importance to the State."))
        assertFalse('⁠' in b.book.text || '­' in b.book.text)
        assertFalse("↩" in b.book.text, "endnote backlinks removed")
    }

    @Test
    fun `gutenberg - NCX chapters from the older format`() {
        val b = BookReader().read(gutenberg)

        assertEquals(ChapterStrategy.NCX, b.plan.strategy)
        val titles = b.chapters.map { it.title }
        assertEquals(23, titles.size)
        assertTrue("Chapter I. LAYING PLANS" in titles && "Chapter XIII. THE USE OF SPIES" in titles)
    }

    @Test
    fun `both editions carry the same main text`() {
        val seText = BookReader().read(se).book.text
        val pgText = BookReader().read(gutenberg).book.text

        listOf("All warfare is based on deception.", "leave an outlet free", "He burns his boats and breaks his cooking-pots")
            .forEach { assertTrue(it in seText && it in pgText, it) }
    }
}
