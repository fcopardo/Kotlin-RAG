package rag.structure

/**
 * One entry of a book's outline or table of contents. [page] is null when it has no target.
 * [offset] is the exact start in the book text when known (EPUB); it's carried to the chapter start.
 */
data class OutlineEntry(val title: String, val page: Int?, val children: List<OutlineEntry> = emptyList(), val offset: Int? = null)

/** Chapter starts from an outline, plus the starts of TOC ("Contents") entries found at any level. */
data class FlattenedOutline(val chapters: List<ChapterStart>, val contents: List<ChapterStart>)

/**
 * Turns a two-level outline into chapters (SPEC §5.3). Pure, shared by PDF bookmarks and EPUB TOCs.
 *
 * Top-level entries are chapters, and their children are sections (ignored), except for **parts**.
 * A top-level entry with children is a part when both hold:
 * - some other top-level entries have no children (a mix of containers and plain chapters, as in
 *   "IX. Interview Questions" next to "VI. Big O"), or its title looks like a part ("Part II",
 *   "Book 1", or a Roman numeral like "IX.");
 * - its children don't look like sections: they aren't numbered under it ("5" → "5.1"), and they
 *   average at least [minChildPages] pages each (sections are short; CtCI's chapters are 6–25 pages).
 * A part's children become chapters titled "Part › Child". The part keeps a chapter of its own
 * only when it starts before its first child (an intro page).
 */
object OutlineChapters {

    const val PART_SEPARATOR = " › "
    private const val minChildPages = 3.0
    private val leadingNumber = Regex("^\\s*(\\d+)\\b")

    private val contentsTitle = Regex("^(table of )?contents$", RegexOption.IGNORE_CASE)
    private val partTitle = Regex("^(part|book)\\b|^[IVXLC]+\\.\\s", RegexOption.IGNORE_CASE)

    fun isContents(title: String) = contentsTitle.matches(title.trim())

    fun flatten(entries: List<OutlineEntry>): FlattenedOutline {
        val contents = mutableListOf<ChapterStart>()
        fun collectContents(e: OutlineEntry) {
            if (isContents(e.title) && e.page != null) contents += ChapterStart(e.title.trim(), e.page, e.offset)
            e.children.forEach(::collectContents)
        }
        entries.forEach(::collectContents)

        fun realChildren(e: OutlineEntry) = e.children.filter { it.page != null && !isContents(it.title) }
        val top = entries.filter { it.page != null && !isContents(it.title) }
        val mixed = top.any { realChildren(it).isEmpty() } && top.any { realChildren(it).isNotEmpty() }

        val topStarts = top.map { it.page!! }.sorted()
        fun looksLikeSections(e: OutlineEntry, children: List<OutlineEntry>): Boolean {
            val number = leadingNumber.find(e.title)?.groupValues?.get(1)
            if (number != null && children.all { it.title.trim().startsWith("$number.") }) return true
            val end = topStarts.firstOrNull { it > e.page!! } ?: (children.maxOf { it.page!! } + 1)
            val firstChild = children.minOf { it.page!! }
            return (end - firstChild).toDouble() / children.size < minChildPages
        }

        val chapters = top.flatMap { e ->
            val children = realChildren(e)
            val isPart = children.isNotEmpty() && (mixed || partTitle.containsMatchIn(e.title.trim())) && !looksLikeSections(e, children)
            if (!isPart) return@flatMap listOf(ChapterStart(e.title.trim(), e.page!!, e.offset))
            val firstChild = children.minOf { it.page!! }
            val intro = if (e.page!! < firstChild) listOf(ChapterStart(e.title.trim(), e.page, e.offset)) else emptyList()
            intro + children.map { ChapterStart(e.title.trim() + PART_SEPARATOR + it.title.trim(), it.page!!, it.offset) }
        }.sortedWith(compareBy({ it.pdfPage }, { it.charOffset ?: 0 }))
        return FlattenedOutline(chapters, contents.sortedBy { it.pdfPage })
    }

    /** The chapter's own title without its part prefix, for matching headings and running titles. */
    fun ownTitle(title: String): String = title.substringAfterLast(PART_SEPARATOR)
}
