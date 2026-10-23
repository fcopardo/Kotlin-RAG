package rag.document

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import rag.BookFormat
import rag.BookSource
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import rag.extract.Normalizer
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Word `.docx` (SPEC §18.4). A `.docx` is a zip whose `word/document.xml` holds the text, so it
 * needs no new dependency: `java.util.zip` opens it and jsoup's XML parser reads it.
 *
 * Each `<w:p>` is a paragraph; a paragraph whose style is `Heading N` (or `Title`) is a heading;
 * each `<w:tbl>` row becomes one line of a table paragraph, as in EPUB.
 */
class DocxBookSource(private val papers: PaperBuilder = PaperBuilder()) : BookSource {
    override val format = BookFormat.DOCX
    override val extractor = "docx/norm-v1"

    private val headingStyle = Regex("^heading\\s*(\\d)$", RegexOption.IGNORE_CASE)

    override fun read(file: File, options: ReadOptions): StructuredBook {
        val (body, title) = try {
            ZipFile(file).use { zip ->
                val main = zip.getEntry("word/document.xml")
                    ?: throw RagException.BookUnreadable("Not a Word document (no word/document.xml): $file")
                val xml = zip.getInputStream(main).use { String(it.readBytes(), Charsets.UTF_8) }
                xml to docProperty(zip)
            }
        } catch (e: ZipException) {
            throw RagException.BookUnreadable("Damaged Word document: $file (${e.message})", e)
        }

        val document = Jsoup.parse(body, "", Parser.xmlParser())
        val out = StringBuilder()
        val headings = mutableListOf<Heading>()

        fun append(text: String): Int {
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length
            out.append(text)
            return start
        }

        // Paragraphs and tables in document order; nested paragraphs inside a table are its rows.
        document.select("w|body > w|p, w|body > w|tbl").forEach { block ->
            if (block.normalName().endsWith("tbl")) {
                val rows = block.select("w|tr").map { tr ->
                    tr.select("w|tc").joinToString(" | ") { Normalizer.normalize(textOf(it)).trim() }
                }.filter { it.isNotBlank() }
                if (rows.isNotEmpty()) append(rows.joinToString("\n"))
                return@forEach
            }
            val text = Normalizer.normalize(textOf(block)).trim()
            if (text.isEmpty()) return@forEach
            val at = append(text)
            level(block)?.let { headings += Heading(text, it, at) }
        }
        if (out.isBlank()) throw RagException.BookUnreadable("Word document has no text: $file")

        val name = title ?: headings.firstOrNull { it.level == 0 }?.title ?: file.nameWithoutExtension
        return papers.build(out.toString(), name, format, extractor, headings, emptyList(), options)
    }

    /**
     * The heading level of a `<w:p>` from its paragraph style, or null for body text. Word's
     * "Title" sits above "Heading 1", like Markdown's `#` above `##`, so it is level 0.
     */
    private fun level(paragraph: Element): Int? {
        val style = paragraph.select("w|pStyle").firstOrNull()?.attr("w:val")?.trim() ?: return null
        if (style.equals("Title", ignoreCase = true)) return 0
        return headingStyle.matchEntire(style)?.groupValues?.get(1)?.toInt()?.coerceIn(1, 6)
    }

    /** A block's visible text: its runs joined, with `<w:br>` and `<w:tab>` as whitespace. */
    private fun textOf(element: Element): String {
        val out = StringBuilder()
        element.select("w|t, w|br, w|tab").forEach { node ->
            when (node.normalName().substringAfter(':')) {
                "t" -> out.append(node.wholeText())
                "br" -> out.append('\n')
                else -> out.append(' ')
            }
        }
        return out.toString()
    }

    /** The document's title from its core properties, when Word recorded one. */
    private fun docProperty(zip: ZipFile): String? {
        val entry = zip.getEntry("docProps/core.xml") ?: return null
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
        return Jsoup.parse(xml, "", Parser.xmlParser()).select("dc|title").firstOrNull()
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
