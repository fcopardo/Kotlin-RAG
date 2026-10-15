package rag.structure

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem

/** Picks chapter starts: PDF outline, then TOC pages, then the whole book (SPEC §5.3). */
class ChapterDetector(private val locator: TocLocator = TocLocator()) {

    /**
     * @param rawPages page texts before cleanup, `rawPages[i]` is PDF page `i + 1`.
     * @param tocPages PDF pages the user says hold the TOC, if any.
     */
    fun detect(document: PDDocument, rawPages: List<String>, tocPages: Set<Int>? = null): ChapterPlan =
        fromOutline(document, rawPages, tocPages)
            ?: tocPages?.takeIf { it.isNotEmpty() }?.let { fromToc(document, rawPages, it) }
            ?: ChapterPlan(ChapterStrategy.FALLBACK, listOf(ChapterStart("Book", 1)), tocPages.orEmpty())

    fun fromOutline(document: PDDocument, rawPages: List<String>, tocPages: Set<Int>? = null): ChapterPlan? {
        val outline = document.documentCatalog.documentOutline ?: return null
        fun pageOf(item: PDOutlineItem) = item.findDestinationPage(document)?.let { document.pages.indexOf(it) + 1 }?.takeIf { it >= 1 }
        // Two levels are enough: top-level entries and, for parts, their children.
        val entries = outline.children().map { item ->
            OutlineEntry(item.title.orEmpty(), pageOf(item), item.children().map { OutlineEntry(it.title.orEmpty(), pageOf(it)) })
        }
        val (chapters, contents) = OutlineChapters.flatten(entries)
        if (chapters.size < 2) return null

        // The outline's TOC entry starts the TOC; it continues while pages still look like a TOC,
        // up to the next entry's page (unlisted front matter can sit in between).
        val outlineToc = contents.flatMap { toc ->
            val next = chapters.firstOrNull { it.pdfPage > toc.pdfPage }?.pdfPage ?: (toc.pdfPage + 1)
            listOf(toc.pdfPage) + ((toc.pdfPage + 1) until next)
                .takeWhile { it <= rawPages.size && TocParser.looksLikeToc(rawPages[it - 1]) }
        }.toSet()
        return ChapterPlan(ChapterStrategy.OUTLINE, chapters, tocPages ?: outlineToc)
    }

    fun fromToc(document: PDDocument, rawPages: List<String>, tocPages: Set<Int>): ChapterPlan? {
        val entries = TocParser.parse(TocLineReader.read(document, tocPages))
        val located = locator.locate(entries, rawPages, searchFrom = tocPages.max() + 1) ?: return null
        if (located.starts.size < 2) return null
        return ChapterPlan(ChapterStrategy.TOC, located.starts, tocPages, located.pageOffset, located.warnings)
    }
}
