package rag.epub

import org.junit.jupiter.api.io.TempDir
import rag.BookFormat
import rag.BookFormats
import rag.BookReader
import rag.PageKind
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import rag.epub.TestEpubs.Doc
import rag.epub.TestEpubs.Toc
import rag.index.UnitKeys
import rag.structure.ChapterStrategy
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubBookSourceTest {

    @TempDir
    lateinit var dir: File

    private val source = EpubBookSource()

    private val docs = listOf(
        Doc("cover.xhtml", "<p>Cover text of the book.</p>"),
        Doc("ch1.xhtml", "<h1>1. The Ride</h1><p>The bike went really fast. Dr. Lee watched from the hill.</p><p>It was over in a minute.</p>"),
        Doc("ch2.xhtml", """<h1>2. Soup</h1><p>The soup was cold.</p><h2 id="s3">3. Bread</h2><p>Bread was warm.</p>"""),
    )
    private val toc = listOf(Toc("1. The Ride", "ch1.xhtml"), Toc("2. Soup", "ch2.xhtml"), Toc("3. Bread", "ch2.xhtml#s3"))

    private fun epub(name: String = "book.epub", block: (File) -> File) = block(File(dir, name))

    private fun read(file: File, options: ReadOptions = ReadOptions()) = source.read(file, options)

    private fun paragraphs(b: StructuredBook) = b.segments.flatMap { s -> s.paragraphs.map { b.text(it.start, it.end) } }

    /** Every unit is trimmed text at its offsets, nested in its chapter (SPEC §11). */
    private fun assertOffsets(b: StructuredBook) {
        b.segments.forEach { s ->
            s.paragraphs.forEach { p ->
                val t = b.text(p.start, p.end)
                assertEquals(t.trim(), t)
                assertTrue(p.start >= s.chapter.charStart && p.end <= s.chapter.charEnd)
                p.phrases.forEach { ph -> assertTrue(ph.start >= p.start && ph.end <= p.end) }
            }
        }
    }

    @Test
    fun `EPUB 3 nav gives chapters, including one that starts at an element id`() {
        val b = read(epub { TestEpubs.build(it, docs, toc) })

        assertEquals(BookFormat.EPUB, b.format)
        assertEquals(ChapterStrategy.NAV, b.plan.strategy)
        assertEquals("Test Book", b.title)
        assertEquals(listOf("Front matter", "1. The Ride", "2. Soup", "3. Bread"), b.chapters.map { it.title })
        assertEquals("3. Bread\n\nBread was warm.", b.text(b.chapters[3]).trimEnd())
        assertTrue(b.text(b.chapters[2]).startsWith("2. Soup\n\nThe soup was cold."))
        assertEquals(
            listOf("Cover text of the book.", "1. The Ride", "The bike went really fast. Dr. Lee watched from the hill.",
                "It was over in a minute.", "2. Soup", "The soup was cold.", "3. Bread", "Bread was warm."),
            paragraphs(b),
        )
        assertOffsets(b)
    }

    @Test
    fun `EPUB 2 NCX gives the same chapters, text and unit keys`() {
        val nav = read(epub("nav.epub") { TestEpubs.build(it, docs, toc) })
        val ncx = read(epub("ncx.epub") { TestEpubs.build(it, docs, toc, withNav = false, withNcx = true) })

        assertEquals(ChapterStrategy.NCX, ncx.plan.strategy)
        assertEquals(nav.book.text, ncx.book.text)
        assertEquals(nav.chapters, ncx.chapters)
        assertEquals(UnitKeys.build(nav, 1).map { it.key }, UnitKeys.build(ncx, 1).map { it.key })
    }

    @Test
    fun `parts give prefixed chapters, numbered sections stay inside their chapter`() {
        val long = (1..20).joinToString(" ") { "Sentence number $it is here." }
        val partDocs = listOf(
            Doc("pre.xhtml", "<h1>Preface</h1><p>$long</p>"),
            Doc("a.xhtml", "<h1>Chapter A</h1><p>$long</p>"),
            Doc("b.xhtml", "<h1>Chapter B</h1><p>$long</p>"),
            Doc("c.xhtml", """<h1>4 Long chapter</h1><h2 id="x">4.1 First</h2><p>$long</p><h2 id="y">4.2 Second</h2><p>Short.</p>"""),
        )
        val partToc = listOf(
            Toc("Preface", "pre.xhtml"),
            Toc("Part I", "a.xhtml", listOf(Toc("Chapter A", "a.xhtml"), Toc("Chapter B", "b.xhtml"))),
            Toc("4 Long chapter", "c.xhtml", listOf(Toc("4.1 First", "c.xhtml#x"), Toc("4.2 Second", "c.xhtml#y"))),
        )

        val b = read(epub { TestEpubs.build(it, partDocs, partToc) }, ReadOptions(locationSize = 50))

        assertEquals(listOf("Preface", "Part I › Chapter A", "Part I › Chapter B", "4 Long chapter"), b.chapters.map { it.title })
        assertTrue(b.text(b.chapters[1]).startsWith("Chapter A"))
    }

    @Test
    fun `footnote markers go, URL notes go, prose notes stay`() {
        val body = """<p>A claim<a epub:type="noteref" href="#n1">1</a> and another<sup><a href="#n2">2</a></sup>.</p>
            <aside epub:type="footnote" id="n1"><p>1. https://example.com/x</p></aside>
            <aside epub:type="footnote" id="n2"><p>2. See the appendix for details.</p></aside>"""
        val endnotes = """<section epub:type="endnotes"><ol><li id="note-1" epub:type="endnote"><p>An endnote with prose. <a href="ch.xhtml" epub:type="backlink">↩</a></p></li></ol></section>"""

        val b = read(epub { TestEpubs.build(it, listOf(Doc("ch.xhtml", body), Doc("notes.xhtml", endnotes))) })

        assertEquals(listOf("A claim and another.", "See the appendix for details.", "An endnote with prose."), paragraphs(b))
        assertFalse("https" in b.book.text)
    }

    @Test
    fun `pre is code with indentation and without line numbers, tables are rows, soft hyphens go`() {
        val body = "<pre>fun main() {\n    println(\"hi\")\n\n}</pre><pre>1 val a = 1\n2 val b = 2</pre>" +
            "<table><tr><th>Name</th><th>Age</th></tr><tr><td>Ann</td><td>30</td></tr></table><p>imple­ment it</p>"

        val b = read(epub { TestEpubs.build(it, listOf(Doc("ch.xhtml", body))) })

        val ps = b.segments.flatMap { it.paragraphs }
        assertEquals(listOf(true, true, false, false), ps.map { it.isCode })
        assertEquals(listOf("fun main() {\n    println(\"hi\")\n}", "val a = 1\nval b = 2", "Name | Age\nAnn | 30", "implement it"), paragraphs(b))
        assertEquals(listOf("fun main() {", "println(\"hi\")", "}"), ps[0].phrases.map { b.text(it.start, it.end) })
    }

    @Test
    fun `a page list gives printed page labels, even mid-paragraph`() {
        val pageDocs = listOf(
            Doc("ch1.xhtml", """<span epub:type="pagebreak" id="pg1" title="i"/><h1>One</h1><p>First page text.</p><span epub:type="pagebreak" id="pg2"/><p>Second page text.</p>"""),
            Doc("ch2.xhtml", """<h1>Two</h1><p>Third <span epub:type="pagebreak" id="pg3"/>page text continues.</p>"""),
        )
        val pages = listOf("i" to "ch1.xhtml#pg1", "2" to "ch1.xhtml#pg2", "3" to "ch2.xhtml#pg3")

        val b = read(epub { TestEpubs.build(it, pageDocs, listOf(Toc("One", "ch1.xhtml"), Toc("Two", "ch2.xhtml")), pageList = pages) })

        val book = b.book
        fun labelAt(s: String) = book.pageLabel(book.pageOf(book.text.indexOf(s)))
        assertEquals(PageKind.PRINT, b.pageKind)
        assertEquals("i", labelAt("First page"))
        assertEquals("2", labelAt("Second page"))
        assertEquals("2", labelAt("Third"))
        assertEquals("3", labelAt("page text continues"))
        assertOffsets(b)
    }

    @Test
    fun `without a page list, locations of the given size`() {
        val b = read(epub { TestEpubs.build(it, docs, toc) }, ReadOptions(locationSize = 40))

        assertEquals(PageKind.LOCATION, b.pageKind)
        assertEquals((b.book.text.length + 39) / 40, b.book.pageCount)
        assertEquals("loc 1", b.book.pageLabel(1))
    }

    @Test
    fun `DRM and fixed layout are rejected with exit code 3, font obfuscation is fine`() {
        fun encryption(algorithm: String) = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
            <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/><enc:CipherData><enc:CipherReference URI="OEBPS/ch1.xhtml"/></enc:CipherData></enc:EncryptedData></encryption>"""
        val cases = mapOf(
            "rights" to mapOf("META-INF/rights.xml" to "<rights/>"),
            "sinf" to mapOf("META-INF/sinf.xml" to "<sinf/>"),
            "aes" to mapOf("META-INF/encryption.xml" to encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc")),
        )
        cases.forEach { (name, extra) ->
            val e = assertFailsWith<RagException.BookUnreadable>(name) { read(epub("$name.epub") { TestEpubs.build(it, docs, toc, extra = extra) }) }
            assertEquals(3, e.exitCode)
            assertContains(e.message!!, "DRM")
        }
        val fixed = assertFailsWith<RagException.BookUnreadable> { read(epub("fixed.epub") { TestEpubs.build(it, docs, toc, fixedLayout = true) }) }
        assertContains(fixed.message!!, "Fixed-layout")

        val fonts = read(epub("fonts.epub") { TestEpubs.build(it, docs, toc, extra = mapOf("META-INF/encryption.xml" to encryption("http://www.idpf.org/2008/embedding"))) })
        assertEquals(4, fonts.chapters.size)
    }

    @Test
    fun `non-linear items, the nav document and the contents page are not text`() {
        val withPopup = docs + Doc("popup.xhtml", "<p>Popup note text.</p>", linear = false)

        val b = read(epub { TestEpubs.build(it, withPopup, toc, navInSpine = true) })

        assertFalse("Popup" in b.book.text)
        assertFalse("Contents" in b.book.text)
    }

    @Test
    fun `without a TOC, leading headings become chapters`() {
        val b = read(epub { TestEpubs.build(it, docs, withNav = false) })

        assertEquals(ChapterStrategy.HEADINGS, b.plan.strategy)
        assertEquals(listOf("Front matter", "1. The Ride", "2. Soup"), b.chapters.map { it.title })
    }

    @Test
    fun `format is sniffed from content, and toc pages are refused for EPUB`() {
        val e = epub { TestEpubs.build(it, docs, toc) }
        val renamed = File(dir, "actually-an-epub.pdf").also { e.copyTo(it) }
        val text = File(dir, "notes.epub").apply { writeText("just text") }

        assertEquals(BookFormat.EPUB, BookFormats.sniff(renamed))
        assertFailsWith<RagException.BookUnreadable> { BookFormats.sniff(text) }
        assertFailsWith<IllegalArgumentException> { BookReader().read(e, tocPages = setOf(1)) }
        assertEquals(4, BookReader().read(renamed).chapters.size)
    }

    @Test
    fun `book language sets the sentence locale, title falls back to the file name`() {
        val b = read(epub("Untitled Book.epub") { TestEpubs.build(it, docs, toc, title = null, language = "en-GB") })

        assertEquals("Untitled Book", b.title)
    }
}
