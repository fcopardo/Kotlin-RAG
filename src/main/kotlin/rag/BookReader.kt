package rag

import org.apache.pdfbox.pdmodel.PDDocument
import rag.extract.ExtractedBook
import rag.extract.PdfExtractor
import rag.structure.Chapter
import rag.structure.ChapterDetector
import rag.structure.ChapterPlan
import rag.structure.Chapters
import rag.structure.SegmentedChapter
import rag.structure.Segmenter
import java.io.File

/** A book's text with its chapters, paragraphs and phrases located in it. */
class StructuredBook(
    val book: ExtractedBook,
    val chapters: List<Chapter>,
    val plan: ChapterPlan,
    val segments: List<SegmentedChapter>,
    val title: String? = null,
) {
    fun text(chapter: Chapter): String = book.text.substring(chapter.charStart, chapter.charEnd + 1)

    fun text(start: Int, end: Int): String = book.text.substring(start, end + 1)
}

/** Reads a PDF into a [StructuredBook]: extract, detect chapters, assemble, locate chapters. */
class BookReader(
    private val extractor: PdfExtractor = PdfExtractor(),
    private val detector: ChapterDetector = ChapterDetector(),
    private val segmenter: Segmenter = Segmenter(),
) {
    fun read(pdf: File, tocPages: Set<Int>? = null): StructuredBook =
        extractor.open(pdf).use { read(it, tocPages) }

    fun read(document: PDDocument, tocPages: Set<Int>? = null): StructuredBook {
        val rawPages = extractor.readPages(document)
        val plan = detector.detect(document, rawPages, tocPages)
        val book = extractor.assemble(rawPages, plan.starts.map { it.title to it.pdfPage }, plan.tocPages)
        val chapters = Chapters.compute(book, plan.starts)
        val title = document.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() }
        return StructuredBook(book, chapters, plan, segmenter.segment(book, chapters), title)
    }

    companion object {
        /** Parses `--toc-pages` values like "5-8" or "2-3,5". */
        fun parsePageRanges(spec: String): Set<Int> = spec.split(',').filter { it.isNotBlank() }.flatMap { part ->
            val bounds = part.trim().split('-').map { it.trim().toInt() }
            require(bounds.size in 1..2 && bounds.all { it >= 1 }) { "bad page range: '$part'" }
            (bounds.first()..bounds.last()).toList()
        }.toSet()
    }
}
