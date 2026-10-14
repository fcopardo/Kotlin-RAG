package rag

import org.apache.pdfbox.pdmodel.PDDocument
import rag.epub.EpubBookSource
import rag.extract.ExtractedBook
import rag.extract.PdfExtractor
import rag.structure.Chapter
import rag.structure.ChapterDetector
import rag.structure.ChapterPlan
import rag.structure.Chapters
import rag.structure.OutlineChapters
import rag.structure.SegmentedChapter
import rag.structure.Segmenter
import java.io.File
import java.util.Locale

/** A book's text with its chapters, paragraphs and phrases located in it. */
class StructuredBook(
    val book: ExtractedBook,
    val chapters: List<Chapter>,
    val plan: ChapterPlan,
    val segments: List<SegmentedChapter>,
    val title: String? = null,
    val format: BookFormat = BookFormat.PDF,
    val pageKind: PageKind = PageKind.PDF,
) {
    fun text(chapter: Chapter): String = book.text.substring(chapter.charStart, chapter.charEnd + 1)

    fun text(start: Int, end: Int): String = book.text.substring(start, end + 1)
}

/** Reads a PDF: extract pages, detect chapters, assemble the text, locate chapters, segment. */
class PdfBookSource(
    private val extractor: PdfExtractor = PdfExtractor(),
    private val detector: ChapterDetector = ChapterDetector(),
) : BookSource {
    override val format = BookFormat.PDF

    /** Without a title in the PDF's metadata, the file name (without extension) is the title. */
    override fun read(file: File, options: ReadOptions): StructuredBook =
        extractor.open(file).use { read(it, options, fallbackTitle = file.nameWithoutExtension) }

    fun read(document: PDDocument, options: ReadOptions = ReadOptions(), fallbackTitle: String? = null): StructuredBook {
        val rawPages = extractor.readPages(document)
        val plan = detector.detect(document, rawPages, options.tocPages)
        val book = extractor.assemble(rawPages, plan.starts.map { OutlineChapters.ownTitle(it.title) to it.pdfPage }, plan.tocPages)
        val chapters = Chapters.compute(book, plan.starts)
        val title = document.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val segmenter = Segmenter(options.locale ?: Locale.ENGLISH)
        return StructuredBook(book, chapters, plan, segmenter.segment(book, chapters), title)
    }
}

/** Reads any supported book, picking the [BookSource] by the file's content. */
class BookReader(
    private val pdf: PdfBookSource = PdfBookSource(),
    private val others: List<BookSource> = listOf(EpubBookSource()),
) {
    fun read(file: File, tocPages: Set<Int>? = null, options: ReadOptions = ReadOptions(tocPages = tocPages)): StructuredBook {
        val format = BookFormats.sniff(file)
        if (format != BookFormat.PDF && options.tocPages != null) {
            throw IllegalArgumentException("--toc-pages only applies to PDFs")
        }
        val source = if (format == BookFormat.PDF) pdf else others.firstOrNull { it.format == format }
            ?: throw RagException.BookUnreadable("${format.name} books aren't supported yet: $file")
        return source.read(file, options)
    }

    fun read(document: PDDocument, tocPages: Set<Int>? = null): StructuredBook =
        pdf.read(document, ReadOptions(tocPages = tocPages))

    companion object {
        /** Parses `--toc-pages` values like "5-8" or "2-3,5". */
        fun parsePageRanges(spec: String): Set<Int> = spec.split(',').filter { it.isNotBlank() }.flatMap { part ->
            val bounds = part.trim().split('-').map { it.trim().toInt() }
            require(bounds.size in 1..2 && bounds.all { it >= 1 }) { "bad page range: '$part'" }
            (bounds.first()..bounds.last()).toList()
        }.toSet()
    }
}
