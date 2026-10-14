package rag.extract

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import rag.RagException
import java.io.File
import java.io.IOException

/**
 * The normalized book text plus where each PDF page starts in it.
 * `pageStarts[i]` is the char offset of PDF page `i + 1`.
 * [codeRanges] are the inclusive char ranges of code listings, in text order.
 */
class ExtractedBook(
    val text: String,
    val pageStarts: IntArray,
    val codeRanges: List<IntRange> = emptyList(),
    /** Display names of pages ("xii", "57", "loc 3"); null means the page number itself (PDF). */
    val pageLabels: List<String>? = null,
) {
    init {
        require(pageLabels == null || pageLabels.size == pageStarts.size) { "one label per page" }
    }

    val pageCount: Int get() = pageStarts.size

    /** Label of 1-based page [page]. */
    fun pageLabel(page: Int): String = pageLabels?.get(page - 1) ?: page.toString()

    /** 1-based PDF page containing the char at [offset]. */
    fun pageOf(offset: Int): Int {
        require(offset in text.indices) { "offset $offset outside book text (length ${text.length})" }
        val found = pageStarts.binarySearch(offset)
        // Several empty pages can share a start offset; the char belongs to the last of them.
        var index = if (found >= 0) found else -found - 2
        while (index + 1 < pageStarts.size && pageStarts[index + 1] == offset) index++
        return index + 1
    }
}

/** Extracts and normalizes text page by page (SPEC §5.1–5.2). */
class PdfExtractor(
    private val boilerplateRemover: BoilerplateRemover = BoilerplateRemover(),
    private val codeListings: CodeListings = CodeListings(),
) {
    fun extract(pdf: File): ExtractedBook = open(pdf).use { extract(it) }

    fun extract(document: PDDocument): ExtractedBook = assemble(readPages(document))

    /** Raw text of each page, `[i]` is PDF page `i + 1`. Throws if there's no text layer. */
    fun readPages(document: PDDocument): List<String> {
        val rawPages = rawPageTexts(document)
        if (rawPages.all { it.isBlank() }) {
            throw RagException.NoTextLayer("PDF has no text layer (scanned/image-only PDFs need OCR, which is out of scope)")
        }
        return rawPages
    }

    /**
     * Cleans, normalizes and joins raw pages into the book text.
     *
     * @param chapterStarts (title, PDF page) pairs, used to remove running chapter titles.
     * @param skipPages PDF pages left out of the text (the TOC). They stay as empty pages so
     *   page numbers and offsets keep lining up.
     */
    fun assemble(
        rawPages: List<String>,
        chapterStarts: List<Pair<String, Int>> = emptyList(),
        skipPages: Set<Int> = emptySet(),
    ): ExtractedBook {
        val kept = rawPages.mapIndexed { i, page -> if (i + 1 in skipPages) "" else Footnotes.clean(page) }
        val cleaned = boilerplateRemover.removeRunningTitles(boilerplateRemover.remove(kept, chapterStarts), chapterStarts)
        val pages = cleaned.map { codeListings.clean(Normalizer.normalize(it)) }

        val text = StringBuilder()
        val pageStarts = IntArray(pages.size) { -1 }
        val codeRanges = mutableListOf<IntRange>()
        var previous: CodeListings.Result? = null
        pages.forEachIndexed { i, page ->
            if (page.text.isEmpty()) return@forEachIndexed
            if (previous != null && pageBreakEndsParagraph(previous!!, page)) text.append('\n')
            val offset = text.length
            pageStarts[i] = offset
            page.codeRanges.forEach { r ->
                val shifted = (r.first + offset)..(r.last + offset)
                val last = codeRanges.lastOrNull()
                // A listing that continues on the next page stays one block.
                if (last != null && shifted.first == last.last + 2) codeRanges[codeRanges.size - 1] = last.first..shifted.last
                else codeRanges += shifted
            }
            text.append(page.text).append('\n')
            previous = page
        }
        // Empty pages share the next page's start offset (or the end, after the last text).
        var next = text.length
        for (i in pageStarts.indices.reversed()) {
            if (pageStarts[i] == -1) pageStarts[i] = next else next = pageStarts[i]
        }
        return ExtractedBook(text.toString(), pageStarts, codeRanges)
    }

    /**
     * Pages are joined with a newline, which continues the paragraph. The page break starts a new
     * paragraph instead when the previous page ends a sentence and the next page doesn't start in
     * lowercase, or when one side is a code listing and the other isn't.
     */
    private fun pageBreakEndsParagraph(previous: CodeListings.Result, next: CodeListings.Result): Boolean {
        val previousEndsInCode = previous.codeRanges.lastOrNull()?.last == previous.text.length - 1
        val nextStartsWithCode = next.codeRanges.firstOrNull()?.first == 0
        if (previousEndsInCode || nextStartsWithCode) return previousEndsInCode != nextStartsWithCode
        return previous.text.last() in ".?!:" && !next.text.first().isLowerCase()
    }

    private fun rawPageTexts(document: PDDocument): List<String> {
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            lineSeparator = "\n"
            paragraphStart = ""
            paragraphEnd = "\n\n"
        }
        return (1..document.numberOfPages).map { page ->
            stripper.startPage = page
            stripper.endPage = page
            stripper.getText(document)
        }
    }

    fun open(pdf: File): PDDocument = try {
        // PDFs encrypted with only an owner password (permissions) open fine and are accepted;
        // PDFs that need a user password to open are rejected.
        Loader.loadPDF(pdf)
    } catch (e: InvalidPasswordException) {
        throw RagException.PdfUnreadable("PDF is encrypted and needs a password: $pdf", e)
    } catch (e: IOException) {
        throw RagException.PdfUnreadable("Cannot read PDF: $pdf (${e.message})", e)
    }
}
