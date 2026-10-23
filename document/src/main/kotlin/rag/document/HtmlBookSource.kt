package rag.document

import org.jsoup.Jsoup
import rag.BookFormat
import rag.BookSource
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import rag.epub.EpubTextBuilder
import rag.extract.Normalizer
import java.io.File

/**
 * A standalone HTML page: a saved article, an exported document, generated documentation
 * (SPEC §18.4). The body is walked by the same builder the EPUB reader uses, so blocks, code and
 * tables come out identically; `<h1>`–`<h6>` give the sections.
 */
class HtmlBookSource(
    private val papers: PaperBuilder = PaperBuilder(),
    private val textBuilder: EpubTextBuilder = EpubTextBuilder(),
) : BookSource {
    override val format = BookFormat.HTML
    override val extractor = "jsoup-${Jsoup::class.java.`package`?.implementationVersion ?: "1"}/norm-v1"

    override fun read(file: File, options: ReadOptions): StructuredBook {
        val html = DocumentCharset.read(file)
        // The EPUB builder marks each element with an id; ask for the headings' ids so their
        // offsets come back exactly, after normalization has moved everything around.
        val document = Jsoup.parse(html, "")
        val headings = document.select("h1, h2, h3, h4, h5, h6").filter { it.text().isNotBlank() }
        headings.forEachIndexed { i, e -> if (e.id().isEmpty()) e.attr("id", "rag-heading-$i") }
        val path = file.name
        val wanted = headings.map { "$path#${it.id()}" }.toSet()

        val built = textBuilder.build(listOf(path to document.outerHtml()), wanted)
        if (built.text.isBlank()) throw RagException.BookUnreadable("HTML page has no text: $file")

        val found = headings.mapNotNull { e ->
            built.targets["$path#${e.id()}"]?.let { at ->
                Heading(Normalizer.normalize(e.text()).trim(), e.normalName().removePrefix("h").toInt(), at)
            }
        }
        val title = document.title().trim().takeIf { it.isNotEmpty() }
            ?: found.firstOrNull { it.level == 1 }?.title
            ?: file.nameWithoutExtension
        return papers.build(built.text, title, format, extractor, found, built.codeRanges, options)
    }
}
