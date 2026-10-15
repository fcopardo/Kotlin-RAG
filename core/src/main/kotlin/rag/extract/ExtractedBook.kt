package rag.extract

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
