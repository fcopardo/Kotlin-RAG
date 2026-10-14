package rag.extract

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/**
 * Builds small PDFs for tests. Each page is a list of lines; "" is a blank line (paragraph gap).
 * Leading spaces indent a line ([INDENT] points each) and aren't drawn.
 */
object TestPdfs {
    private val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private const val FONT_SIZE = 11f
    private const val LEADING = 14f
    private const val INDENT = 6f

    /** @param outline top-level bookmarks as (title, 1-based PDF page). */
    fun build(pages: List<List<String>>, outline: List<Pair<String, Int>> = emptyList()): PDDocument {
        val document = PDDocument()
        pages.forEach { lines ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            if (lines.isEmpty()) return@forEach
            PDPageContentStream(document, page).use { content ->
                var y = page.mediaBox.height - 60f
                lines.forEach { line ->
                    val text = line.trimStart(' ')
                    if (text.isNotEmpty()) {
                        content.beginText()
                        content.setFont(font, FONT_SIZE)
                        content.newLineAtOffset(60f + INDENT * (line.length - text.length), y)
                        content.showText(text)
                        content.endText()
                    }
                    y -= LEADING
                }
            }
        }
        if (outline.isNotEmpty()) {
            val root = PDDocumentOutline()
            outline.forEach { (title, pdfPage) ->
                root.addLast(PDOutlineItem().apply {
                    this.title = title
                    destination = PDPageFitDestination().apply { page = document.getPage(pdfPage - 1) }
                })
            }
            document.documentCatalog.documentOutline = root
        }
        return document
    }
}
