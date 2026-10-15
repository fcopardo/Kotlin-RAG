package rag

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.util.Version
import rag.extract.PdfExtractor
import rag.structure.ChapterDetector
import rag.structure.Chapters
import rag.structure.OutlineChapters
import rag.structure.Segmenter
import java.io.File
import java.util.Locale

/** Reads a PDF: extract pages, detect chapters, assemble the text, locate chapters, segment. */
class PdfBookSource(
    private val pdf: PdfExtractor = PdfExtractor(),
    private val detector: ChapterDetector = ChapterDetector(),
) : BookSource {
    override val format = BookFormat.PDF
    override val extractor = "pdfbox-${Version.getVersion()}/norm-v1"

    /** Without a title in the PDF's metadata, the file name (without extension) is the title. */
    override fun read(file: File, options: ReadOptions): StructuredBook =
        pdf.open(file).use { read(it, options, fallbackTitle = file.nameWithoutExtension) }

    fun read(document: PDDocument, options: ReadOptions = ReadOptions(), fallbackTitle: String? = null): StructuredBook {
        val rawPages = pdf.readPages(document)
        val plan = detector.detect(document, rawPages, options.tocPages)
        val book = pdf.assemble(rawPages, plan.starts.map { OutlineChapters.ownTitle(it.title) to it.pdfPage }, plan.tocPages)
        val chapters = Chapters.compute(book, plan.starts)
        val title = document.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val segmenter = Segmenter(options.locale ?: Locale.ENGLISH)
        return StructuredBook(book, chapters, plan, segmenter.segment(book, chapters), title, extractor = extractor)
    }
}
