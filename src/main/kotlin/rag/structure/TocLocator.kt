package rag.structure

/** Where a chapter starts: its title and 1-based PDF page. */
data class ChapterStart(val title: String, val pdfPage: Int)

/** Result of mapping TOC printed pages to PDF pages. */
data class LocatedToc(val starts: List<ChapterStart>, val pageOffset: Int, val warnings: List<String>)

/**
 * Maps TOC entries to PDF pages (SPEC §5.3, strategy 2). Pure: works on page texts.
 *
 * The offset between printed and PDF pages comes from the first entry's title line on a page
 * after the TOC. Each entry is then checked within ±[window] pages; when found somewhere else,
 * the difference carries over to later entries (e.g. an unnumbered full-page figure).
 */
class TocLocator(private val window: Int = 2) {

    /**
     * @param pages page texts, `pages[i]` is PDF page `i + 1`.
     * @param searchFrom first PDF page that can hold a chapter (the page after the TOC).
     * @return null when the first entry's title can't be found.
     */
    fun locate(entries: List<TocEntry>, pages: List<String>, searchFrom: Int): LocatedToc? {
        if (entries.isEmpty()) return null
        val first = entries.first()
        val firstPage = (searchFrom..pages.size).firstOrNull { TitleMatcher.containsLine(pages[it - 1], first.title) }
            ?: return null
        val offset = firstPage - first.printedPage

        val warnings = mutableListOf<String>()
        var drift = 0
        var previous = firstPage
        val starts = entries.map { e ->
            val expected = (e.printedPage + offset + drift).coerceIn(previous, pages.size)
            val found = candidates(expected, previous, pages.size)
                .firstOrNull { TitleMatcher.containsLine(pages[it - 1], e.title) }
            val page = if (found != null) {
                drift += found - expected
                found
            } else {
                warnings += "TOC entry '${e.title}' not found near PDF page $expected; using it anyway"
                expected
            }
            previous = page
            ChapterStart(e.title, page)
        }
        return LocatedToc(starts, offset, warnings)
    }

    /** Pages within the window, nearest first, never before [min]. */
    private fun candidates(expected: Int, min: Int, max: Int): List<Int> =
        (0..window).flatMap { d -> listOf(expected + d, expected - d) }
            .distinct()
            .filter { it in min..max }
}
