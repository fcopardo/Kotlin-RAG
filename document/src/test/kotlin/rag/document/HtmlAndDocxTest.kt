package rag.document

import org.junit.jupiter.api.io.TempDir
import rag.BookFormats
import rag.BookFormat
import rag.structure.ChapterStrategy
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HtmlAndDocxTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `an HTML page becomes a paper with its headings`() {
        val file = File(dir, "page.html").apply {
            writeText(
                """
                <!DOCTYPE html>
                <html><head><title>Riding in the rain</title><style>p { color: red }</style></head>
                <body>
                  <h1>Riding in the rain</h1>
                  <p>Wet roads need longer braking distances.</p>
                  <h2>Tyres</h2>
                  <p>Lower pressure gives more grip on wet tarmac.</p>
                  <table><tr><th>Surface</th><th>Grip</th></tr><tr><td>Tarmac</td><td>Good</td></tr></table>
                  <pre>tyre --pressure 70</pre>
                  <script>ignored()</script>
                </body></html>
                """.trimIndent(),
            )
        }

        assertEquals(BookFormat.HTML, BookFormats.sniff(file))
        val book = HtmlBookSource().read(file)

        assertEquals("Riding in the rain", book.title)
        assertEquals("paper", book.kind)
        assertEquals(ChapterStrategy.MARKDOWN, book.plan.strategy)
        assertEquals(listOf("Riding in the rain", "Riding in the rain › Tyres"), book.chapters.filter { it.number > 0 }.map { it.title })
        assertTrue("ignored()" !in book.book.text, "scripts are left out")
        assertTrue("color: red" !in book.book.text, "styles are left out")
        assertTrue("Surface | Grip" in book.book.text, "a table row per line")
        assertTrue(book.segments.flatMap { it.paragraphs }.any { it.isCode }, "<pre> is code")
    }

    @Test
    fun `a Word document becomes a paper with its heading styles`() {
        val file = docx(
            dir,
            paragraphs = listOf(
                "Title" to "Service agreement",
                "Normal" to "This document describes the service.",
                "Heading 1" to "Scope",
                "Normal" to "The supplier provides maintenance during business hours.",
                "Heading 2" to "Exclusions",
                "Normal" to "Hardware faults are excluded from the service.",
            ),
        )

        assertEquals(BookFormat.DOCX, BookFormats.sniff(file))
        val book = DocxBookSource().read(file)

        assertEquals("Service agreement", book.title)
        assertEquals("paper", book.kind)
        assertEquals(
            listOf("Service agreement", "Service agreement › Scope", "Service agreement › Scope › Exclusions"),
            book.chapters.filter { it.number > 0 }.map { it.title },
        )
        assertTrue("Hardware faults are excluded" in book.book.text)
        assertTrue("Widget | Count" in book.book.text, "table rows are kept")
    }

    /** A minimal but real .docx: a zip with the parts Word requires. */
    private fun docx(dir: File, paragraphs: List<Pair<String, String>>): File {
        fun para(style: String, text: String) =
            """<w:p><w:pPr><w:pStyle w:val="${style.replace(" ", "")}"/></w:pPr><w:r><w:t>${text}</w:t></w:r></w:p>"""
        val table = """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Widget</w:t></w:r></w:p></w:tc>""" +
            """<w:tc><w:p><w:r><w:t>Count</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""
        val body = paragraphs.joinToString("") { (style, text) -> para(style, text) } + table
        val document = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
            <w:body>$body</w:body></w:document>"""
        val core = """<?xml version="1.0" encoding="UTF-8"?>
            <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
              xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Service agreement</dc:title></cp:coreProperties>"""
        val file = File(dir, "agreement.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "[Content_Types].xml" to """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
                "word/document.xml" to document,
                "docProps/core.xml" to core,
            ).forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 1_577_836_800_000L })
                zip.write(content.toByteArray())
            }
        }
        return file
    }
}
