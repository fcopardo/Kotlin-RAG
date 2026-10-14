package rag.structure

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** Reads TOC pages as lines with their starting x position, so indentation tells chapters from sections. */
object TocLineReader {

    fun read(document: PDDocument, pages: Collection<Int>): List<TocLine> {
        val stripper = LineStripper()
        pages.filter { it in 1..document.numberOfPages }.sorted().forEach { page ->
            stripper.startPage = page
            stripper.endPage = page
            stripper.getText(document)
            stripper.flush()
        }
        return stripper.lines
    }

    private class LineStripper : PDFTextStripper() {
        val lines = mutableListOf<TocLine>()
        private val current = StringBuilder()
        private var x: Float? = null

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            if (x == null && textPositions.isNotEmpty()) x = textPositions.first().xDirAdj
            current.append(text)
        }

        override fun writeWordSeparator() {
            current.append(' ')
        }

        override fun writeLineSeparator() = flush()

        fun flush() {
            if (current.isNotBlank()) lines += TocLine(current.toString().trim(), x ?: 0f)
            current.clear()
            x = null
        }
    }
}
