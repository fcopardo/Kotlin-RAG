package rag.extract

/**
 * Removes running headers and footers (SPEC §5.1).
 *
 * The first and last [edgeLines] non-empty lines of each page are candidates. Candidates are
 * compared by a key: their words of two or more letters, lowercased, so page numbers and OCR
 * noise in separators ("… I 6th Edition 195", "… \ 6th Edition 196") don't split one footer into
 * many. A line without such words keys on its digits masked (`#`), which catches page numbers.
 * Lines with neither letters nor digits (e.g. a closing `}`) are never boilerplate.
 *
 * - **Book-wide:** a key on more than [minPageFraction] of pages, and on at least [minPages]
 *   pages, is removed everywhere. The floor protects short documents.
 * - **Per chapter:** see [removeRunningTitles].
 */
class BoilerplateRemover(
    private val edgeLines: Int = 2,
    private val minPageFraction: Double = 0.30,
    private val minPages: Int = 3,
    private val chapterFraction: Double = 0.40,
) {
    private val digits = Regex("\\d+")
    private val word = Regex("\\p{L}{2,}")
    private val hasLetterOrDigit = Regex("[\\p{L}\\p{N}]")

    /**
     * @param chapterStarts (title, 1-based PDF page): on a chapter's first page, a line equal to its
     *   title is kept even when its key is boilerplate, since a heading and its running headers share a key.
     */
    fun remove(pages: List<String>, chapterStarts: List<Pair<String, Int>> = emptyList()): List<String> {
        val boilerplate = repeatedKeys(pages, pages.indices.toList(), minCount = { n -> maxOf(minPages, (n * minPageFraction).toInt() + 1) })
        val headings = chapterStarts.groupBy({ it.second - 1 }, { stripPageNumbers(Normalizer.hashNormalize(it.first)) })
        return pages.mapIndexed { i, page ->
            val keep = headings[i].orEmpty()
            dropKeys(page, boilerplate) { line -> stripPageNumbers(Normalizer.hashNormalize(line)) in keep }
        }
    }

    /**
     * Removes running chapter headers, which repeat only within their chapter and so stay under
     * the book-wide fraction. Within each chapter, on every page but its first:
     * - an edge line whose key appears on at least [chapterFraction] of the chapter's other pages
     *   (and at least [minPages]) is removed, e.g. "Solutions to Chapter 1 | Arrays and Strings";
     * - an edge line equal to the chapter title once page numbers at either end are stripped is
     *   removed even in short chapters, e.g. "1. Composable functions 4" or "5 Writing your first class 26".
     * The chapter's first page keeps its heading.
     *
     * @param chapterStarts (title, 1-based PDF page) in page order; titles without part prefixes.
     */
    fun removeRunningTitles(pages: List<String>, chapterStarts: List<Pair<String, Int>>): List<String> {
        if (chapterStarts.isEmpty()) return pages
        val result = pages.toMutableList()
        chapterStarts.forEachIndexed { c, (title, startPage) ->
            val end = (chapterStarts.getOrNull(c + 1)?.second?.minus(1) ?: pages.size).coerceAtMost(pages.size)
            val body = ((startPage + 1)..end).map { it - 1 }.filter { it in pages.indices }
            if (body.isEmpty()) return@forEachIndexed
            val repeated = repeatedKeys(pages, body, minCount = { n -> maxOf(minPages, kotlin.math.ceil(n * chapterFraction).toInt()) })
                .filter { word.containsMatchIn(it) }.toSet()
            val target = stripPageNumbers(Normalizer.hashNormalize(title))
            body.forEach { i ->
                val lines = result[i].lines()
                val drop = candidateLineIndices(lines).filter { idx ->
                    key(lines[idx]) in repeated || stripPageNumbers(Normalizer.hashNormalize(lines[idx])) == target
                }.toSet()
                if (drop.isNotEmpty()) result[i] = lines.filterIndexed { index, _ -> index !in drop }.joinToString("\n")
            }
        }
        return result
    }

    /** Keys of edge lines found on at least `minCount(pageIndices.size)` of the given pages. */
    private fun repeatedKeys(pages: List<String>, pageIndices: List<Int>, minCount: (Int) -> Int): Set<String> {
        val count = HashMap<String, Int>()
        pageIndices.forEach { i ->
            val lines = pages[i].lines()
            candidateLineIndices(lines).map { key(lines[it]) }.filter { it.isNotEmpty() }.toSet()
                .forEach { count.merge(it, 1, Int::plus) }
        }
        val needed = minCount(pageIndices.size)
        return count.filterValues { it >= needed }.keys
    }

    private fun dropKeys(page: String, keys: Set<String>, keep: (String) -> Boolean = { false }): String {
        if (keys.isEmpty()) return page
        val lines = page.lines()
        val drop = candidateLineIndices(lines).filter { key(lines[it]) in keys && !keep(lines[it]) }.toSet()
        return if (drop.isEmpty()) page else lines.filterIndexed { index, _ -> index !in drop }.joinToString("\n")
    }

    private val pageNumberPrefix = Regex("^\\d+\\s+")
    private val pageNumberSuffix = Regex("\\s+\\d+$")

    /** Drops a page number at either end; applied to both the line and the title, so chapter numbers survive. */
    private fun stripPageNumbers(normalized: String): String =
        pageNumberSuffix.replace(pageNumberPrefix.replace(normalized, ""), "")

    private fun candidateLineIndices(lines: List<String>): Set<Int> {
        val nonEmpty = lines.indices.filter { lines[it].isNotBlank() }
        return (nonEmpty.take(edgeLines) + nonEmpty.takeLast(edgeLines)).toSet()
    }

    /** Words of 2+ letters; else the digit-masked line; empty (never boilerplate) without letters or digits. */
    private fun key(line: String): String {
        val words = word.findAll(line.lowercase()).map { it.value }.toList()
        if (words.isNotEmpty()) return words.joinToString(" ")
        val trimmed = line.trim()
        return if (hasLetterOrDigit.containsMatchIn(trimmed)) digits.replace(trimmed, "#") else ""
    }
}
