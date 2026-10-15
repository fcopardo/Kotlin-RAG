package rag.summarize

/** A paragraph as input to the summary tree. */
data class TreeParagraph(val text: String, val isCode: Boolean, val start: Int, val end: Int, val pageStart: Int, val pageEnd: Int)

/** A span of consecutive paragraphs (or summaries) with the text to summarize. */
data class TreeSpan(val text: String, val start: Int, val end: Int, val pageStart: Int, val pageEnd: Int)

/** Section packing and reduction rules of the summary tree (SPEC §5.7.2). Pure. */
object SummaryTree {
    const val SECTION_TOKENS = 1500
    const val CODE_LINES = 15
    const val REDUCE_TOKENS = 2500
    const val GROUP_SIZE = 12

    const val SECTION_WORDS = 120
    const val GROUP_WORDS = 150
    const val CHAPTER_WORDS = 200
    const val BOOK_WORDS = 250

    private val word = Regex("\\S+")

    /** Tokens approximated as words × 1.3, as in §5.4. */
    fun tokens(text: String): Double = word.findAll(text).count() * 1.3

    /** A code paragraph contributes at most [CODE_LINES] lines, so listings can't fill the context. */
    fun inputText(p: TreeParagraph): String {
        if (!p.isCode) return p.text
        val lines = p.text.lines()
        return if (lines.size <= CODE_LINES) p.text else lines.take(CODE_LINES).joinToString("\n") + "\n…"
    }

    /** Consecutive paragraphs packed greedily up to [SECTION_TOKENS] tokens. */
    fun sections(paragraphs: List<TreeParagraph>): List<TreeSpan> {
        val groups = mutableListOf<MutableList<TreeParagraph>>()
        var size = 0.0
        paragraphs.forEach { p ->
            val t = tokens(inputText(p))
            if (groups.isEmpty() || size + t > SECTION_TOKENS) {
                groups += mutableListOf(p)
                size = t
            } else {
                groups.last() += p
                size += t
            }
        }
        return groups.map { g -> TreeSpan(g.joinToString("\n\n", transform = ::inputText), g.first().start, g.last().end, g.first().pageStart, g.last().pageEnd) }
    }

    /** Whether these summaries are too long together to summarize in one call. */
    fun needsReduction(texts: List<String>): Boolean = texts.sumOf(::tokens) > REDUCE_TOKENS

    /** Indices of consecutive groups of at most [GROUP_SIZE]. */
    fun groups(count: Int): List<List<Int>> = (0 until count).chunked(GROUP_SIZE)
}
