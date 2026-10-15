package rag.structure

/** How chapters were found: PDF outline or TOC pages; EPUB 3 nav, EPUB 2 NCX or headings; or one chapter. */
enum class ChapterStrategy { OUTLINE, TOC, NAV, NCX, HEADINGS, FALLBACK }

/**
 * Where chapters start, before the book text is assembled.
 * [tocPages] are PDF pages holding the TOC; they're left out of the book text.
 */
data class ChapterPlan(
    val strategy: ChapterStrategy,
    val starts: List<ChapterStart>,
    val tocPages: Set<Int> = emptySet(),
    val pageOffset: Int? = null,
    val warnings: List<String> = emptyList(),
)
