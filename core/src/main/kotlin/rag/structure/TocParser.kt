package rag.structure

/** One text line of a TOC page and the x position where it starts (PDF points). */
data class TocLine(val text: String, val x: Float)

/** A top-level TOC entry with an Arabic printed page number. */
data class TocEntry(val title: String, val printedPage: Int)

/** Parses TOC lines into chapter entries (SPEC §5.3, strategy 2). Pure: no PDF dependency. */
object TocParser {

    private val entry = Regex(
        "^(?<title>.+?)[\\s.·•∙⋅‧…_-]{2,}(?<page>\\d+|[ivxlcdm]+)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val contentsHeading = Regex("^(table of )?contents$", RegexOption.IGNORE_CASE)
    private val arabic = Regex("\\d+")

    /** Lines indented up to this much past the leftmost entry still count as top level. */
    private const val INDENT_TOLERANCE = 3f

    /** A run of dot leaders, even when OCR lost the page number after it. */
    private val leaders = Regex("[.·•∙⋅‧…](\\s*[.·•∙⋅‧…]){4,}")

    /**
     * True when at least half of the page's non-blank lines (minimum 2) look like TOC entries: a
     * full entry, or a line with a dot-leader run (OCR sometimes drops the page number).
     */
    fun looksLikeToc(pageText: String): Boolean {
        val lines = pageText.lines().map { it.trim() }.filter { it.isNotEmpty() && !contentsHeading.matches(it) }
        val entries = lines.count { entry.matches(it) || leaders.containsMatchIn(it) }
        return entries >= 2 && entries * 2 >= lines.size
    }

    fun parse(lines: List<TocLine>): List<TocEntry> {
        val matched = lines.mapNotNull { line ->
            val text = line.text.trim()
            if (contentsHeading.matches(text)) return@mapNotNull null
            val m = entry.matchEntire(text) ?: return@mapNotNull null
            Triple(m.groups["title"]!!.value.trim(), m.groups["page"]!!.value, line.x)
        }
        if (matched.isEmpty()) return emptyList()

        val topLevelX = matched.minOf { it.third } + INDENT_TOLERANCE
        return matched
            .filter { (_, _, x) -> x <= topLevelX }
            .filter { (_, page, _) -> arabic.matches(page) } // Roman numerals: front matter
            .map { (title, page, _) -> TocEntry(title, page.toInt()) }
    }
}
