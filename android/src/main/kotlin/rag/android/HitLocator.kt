package rag.android

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import rag.epub.EpubPackage
import rag.epub.EpubTextBuilder
import java.io.File

/**
 * Finds a search hit in its book file, so an app can open the book there (docs/ANDROID.md §7):
 * for a PDF, the areas of the paragraph on its page, to highlight on a rendered page; for an EPUB,
 * the XHTML document that contains it, to show in a WebView.
 *
 * Text is matched on letters and digits only, lowercased: hyphenation, ligatures, spacing and
 * punctuation differ between the stored text and the file, but the letters don't.
 */
class HitLocator(context: Context) {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * Where [paragraph] is on PDF page [page] (1-based), one rectangle per line, as fractions of the
     * page's width and height (0..1, from the top left), ready to scale onto a rendered bitmap. A
     * paragraph that continues on the next page is covered up to the end of this one. Empty when
     * the text can't be found on the page.
     */
    fun pdfHighlights(file: File, page: Int, paragraph: String): List<RectF> = PDDocument.load(file).use { document ->
        if (page !in 1..document.numberOfPages) return emptyList()
        val positions = PositionStripper().apply {
            startPage = page
            endPage = page
            getText(document)
        }.positions
        // The page's letters and digits, each with the glyph it came from.
        val chars = StringBuilder()
        val glyphs = ArrayList<TextPosition>()
        positions.forEach { p ->
            folded(p.unicode).forEach { c -> chars.append(c); glyphs += p }
        }
        val target = folded(paragraph)
        if (target.isEmpty()) return emptyList()
        // Find where the paragraph starts (its first 40 letters), then follow it as far as this page goes.
        val start = chars.indexOf(target.take(PREFIX)).takeIf { it >= 0 } ?: return emptyList()
        var end = start
        while (end < chars.length && end - start < target.length && chars[end] == target[end - start]) end++
        val box = document.getPage(page - 1).cropBox
        lines(glyphs.subList(start, end).distinct()).map { line ->
            RectF(
                (line.minOf { it.xDirAdj } / box.width).coerceIn(0f, 1f),
                (line.minOf { it.yDirAdj - it.heightDir } / box.height).coerceIn(0f, 1f),
                (line.maxOf { it.xDirAdj + it.widthDirAdj } / box.width).coerceIn(0f, 1f),
                (line.maxOf { it.yDirAdj } / box.height).coerceIn(0f, 1f),
            )
        }
    }

    /** The zip path of the EPUB document (in reading order) that contains [text], or null. */
    fun epubDocument(file: File, text: String): String? = EpubPackage.open(file).use { epub ->
        val target = folded(text).take(PREFIX).takeIf { it.isNotEmpty() } ?: return null
        epub.spine.filter { !it.isNav }.firstOrNull { item ->
            val html = epub.read(item.path) ?: return@firstOrNull false
            folded(EpubTextBuilder().build(listOf(item.path to html), emptySet()).text).contains(target)
        }?.path
    }

    /** Glyphs grouped into lines: a new line starts where the baseline moves by more than half a glyph. */
    private fun lines(glyphs: List<TextPosition>): List<List<TextPosition>> {
        val lines = mutableListOf<MutableList<TextPosition>>()
        glyphs.forEach { g ->
            val line = lines.lastOrNull()
            if (line != null && kotlin.math.abs(line.last().yDirAdj - g.yDirAdj) < g.heightDir / 2) line += g else lines += mutableListOf(g)
        }
        return lines
    }

    private class PositionStripper : PDFTextStripper() {
        val positions = mutableListOf<TextPosition>()

        init {
            sortByPosition = true
        }

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            positions += textPositions
        }
    }

    private companion object {
        /** Letters to match a paragraph's start by: enough to be unique on a page, short enough to survive edits. */
        const val PREFIX = 40

        /** NFKC (ligatures → letters), lowercase, letters and digits only. No regexes: it runs per glyph. */
        fun folded(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
    }
}
