package rag.extract

/**
 * Removes running headers and footers (SPEC §5.1).
 *
 * The first and last [edgeLines] non-empty lines of each page are candidates. A candidate is
 * boilerplate when its key (trimmed, digits replaced by `#`) appears on more than
 * [minPageFraction] of pages and on at least [minPages] pages. The [minPages] floor stops
 * short documents from losing real content: in a 3-page PDF, one page is already 33%.
 */
class BoilerplateRemover(
    private val edgeLines: Int = 2,
    private val minPageFraction: Double = 0.30,
    private val minPages: Int = 3,
) {
    private val digits = Regex("\\d+")

    fun remove(pages: List<String>): List<String> {
        val candidatesPerPage = pages.map { candidateLineIndices(it.lines()) }

        val pageCountByKey = HashMap<String, Int>()
        pages.forEachIndexed { i, page ->
            val lines = page.lines()
            candidatesPerPage[i].map { key(lines[it]) }.toSet()
                .forEach { pageCountByKey.merge(it, 1, Int::plus) }
        }
        val boilerplate = pageCountByKey
            .filter { (_, count) -> count >= minPages && count > pages.size * minPageFraction }
            .keys

        return pages.mapIndexed { i, page ->
            val lines = page.lines()
            val drop = candidatesPerPage[i].filter { key(lines[it]) in boilerplate }.toSet()
            lines.filterIndexed { index, _ -> index !in drop }.joinToString("\n")
        }
    }

    /**
     * Removes running chapter titles, which repeat only within their chapter and so stay under
     * the page fraction. On each page after a chapter's first page, an edge line is dropped when
     * it equals the chapter title once a leading or trailing page number is stripped
     * (e.g. "1. Composable functions 4"). The chapter's first page keeps its heading.
     *
     * @param chapterStarts (title, 1-based PDF page) in page order.
     */
    fun removeRunningTitles(pages: List<String>, chapterStarts: List<Pair<String, Int>>): List<String> {
        if (chapterStarts.isEmpty()) return pages
        return pages.mapIndexed { i, page ->
            val pdfPage = i + 1
            val (title, startPage) = chapterStarts.lastOrNull { it.second <= pdfPage } ?: return@mapIndexed page
            if (pdfPage == startPage) return@mapIndexed page
            val target = Normalizer.hashNormalize(title)
            val lines = page.lines()
            val drop = candidateLineIndices(lines).filter { runningTitleKey(lines[it]) == target }.toSet()
            if (drop.isEmpty()) page else lines.filterIndexed { index, _ -> index !in drop }.joinToString("\n")
        }
    }

    private val pageNumberPrefix = Regex("^\\d+\\s+")
    private val pageNumberSuffix = Regex("\\s+\\d+$")

    private fun runningTitleKey(line: String): String {
        val normalized = Normalizer.hashNormalize(line)
        return pageNumberSuffix.replace(pageNumberPrefix.replace(normalized, ""), "")
    }

    private fun candidateLineIndices(lines: List<String>): Set<Int> {
        val nonEmpty = lines.indices.filter { lines[it].isNotBlank() }
        return (nonEmpty.take(edgeLines) + nonEmpty.takeLast(edgeLines)).toSet()
    }

    private fun key(line: String): String = digits.replace(line.trim(), "#")
}
