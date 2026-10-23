package rag.document

import rag.BookFormat
import rag.DocumentKinds
import rag.PageKind
import rag.ReadOptions
import rag.StructuredBook
import rag.extract.ExtractedBook
import rag.structure.ChapterPlan
import rag.structure.ChapterStart
import rag.structure.ChapterStrategy
import rag.structure.Chapters
import rag.structure.OutlineChapters
import rag.structure.Segmenter
import java.util.Locale

/** A heading found in a document: its text, its level (1 = top) and where it starts in the text. */
data class Heading(val title: String, val level: Int, val offset: Int)

/**
 * Turns a document's text into a **paper** or a **contract** (SPEC §18.2), picking whichever its
 * structure says it is:
 *
 * - a **contract** when it has nesting clause numbers *and* reads like an agreement
 *   ([ClauseDetector]): contract > clause > paragraph > sentence, titled `4. Term › 4.2 Renewal`;
 * - else a **paper** divided by its own headings, nested headings joined into a path title
 *   (`Introduction › Background`), the way PDF parts are;
 * - else a paper divided by topic ([TextTiling]), each section titled by the opening words of its
 *   first paragraph.
 */
class DocumentBuilder(private val segmenter: Segmenter = Segmenter()) {

    fun build(
        text: String,
        title: String?,
        format: BookFormat,
        extractor: String,
        headings: List<Heading> = emptyList(),
        codeRanges: List<IntRange> = emptyList(),
        options: ReadOptions = ReadOptions(),
    ): StructuredBook {
        val size = options.locationSize.coerceAtLeast(1)
        val count = maxOf(1, (text.length + size - 1) / size)
        val book = ExtractedBook(text, IntArray(count) { it * size }, codeRanges, List(count) { "loc ${it + 1}" })

        // Clauses win over headings: a contract's heading styles are its clause numbers anyway,
        // and the check needs legal wording too, so a numbered paper stays a paper.
        val clauses = ClauseDetector.detect(text)
        val (kind, strategy, starts) = when {
            clauses != null -> Triple(DocumentKinds.CONTRACT, ChapterStrategy.CLAUSES, pathTitles(clauses, size))
            headings.size >= 2 -> Triple(DocumentKinds.PAPER, ChapterStrategy.MARKDOWN, pathTitles(headings, size))
            else -> Triple(DocumentKinds.PAPER, ChapterStrategy.IDEAS, ideas(book, size))
        }
        val plan = ChapterPlan(
            if (starts.size >= 2) strategy else ChapterStrategy.FALLBACK,
            starts.takeIf { it.size >= 2 } ?: listOf(ChapterStart(title ?: "Document", 1, 0)),
        )
        val chapters = Chapters.compute(book, plan.starts)
        val locale = options.locale ?: Locale.ENGLISH
        return StructuredBook(
            book, chapters, plan, Segmenter(locale).segment(book, chapters),
            title = title, format = format, pageKind = PageKind.LOCATION, extractor = extractor,
            kind = kind.name,
        )
    }

    /** Each heading becomes a section, titled with the path of the headings above it. */
    private fun pathTitles(headings: List<Heading>, locationSize: Int): List<ChapterStart> {
        val open = mutableListOf<Heading>()
        return headings.sortedBy { it.offset }.map { heading ->
            while (open.isNotEmpty() && open.last().level >= heading.level) open.removeAt(open.size - 1)
            val path = (open + heading).joinToString(OutlineChapters.PART_SEPARATOR) { it.title }
            open += heading
            ChapterStart(path, heading.offset / locationSize + 1, heading.offset)
        }
    }

    /** Topic shifts, for a document with no headings. */
    private fun ideas(book: ExtractedBook, locationSize: Int): List<ChapterStart> {
        if (book.text.isEmpty()) return emptyList()
        val paragraphs = segmenter.paragraphs(book, 0, book.text.length - 1)
        if (paragraphs.isEmpty()) return emptyList()
        val texts = paragraphs.map { book.text.substring(it.start, it.end + 1) }
        return TextTiling.cut(texts).map { at ->
            val start = paragraphs[at].start
            ChapterStart(openingWords(texts[at]), start / locationSize + 1, start)
        }
    }

    /** A section's title when the document gives none: the opening words of its first paragraph. */
    private fun openingWords(paragraph: String, max: Int = 60): String {
        val line = paragraph.replace('\n', ' ').trim()
        if (line.length <= max) return line
        val cut = line.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return line.substring(0, cut).trimEnd(',', ';', ':', '.') + "…"
    }
}
