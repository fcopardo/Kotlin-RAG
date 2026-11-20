package rag.code

/** A block's header: [text] normalized, [start] = offset of its first non-whitespace character (or the `{` if empty). */
internal data class Header(val text: String, val start: Int)

internal object Headers {
    private val CONTINUATION_WORDS = listOf("where", "throws", "extends", "implements")

    /**
     * The header of the block whose `{` is at [open] (plan step 2, part C3). [floor] is the offset of
     * the parent block's `{`, or -1 at the top: nothing at or before it is ever part of the header.
     *
     * 1. Same line: scan from the line's start to [open], tracking `(`+`[` minus `)`+`]` from 0 at the
     *    line start. Each `;`, `{` or `}` seen while that count is exactly 0 is a boundary. The header
     *    starts after the last boundary, else at the line start (but always after [floor]).
     * 2. Only if it started at the line start (no boundary, floor not on this line): while the collected
     *    text (from its start to [open]) needs more, prepend the previous line. It needs more when it is
     *    blank, OR has more `)`+`]` than `(`+`[`, OR, trimmed, starts with `:`, `->`, `,` or one of
     *    [CONTINUATION_WORDS] as a whole word, OR the previous line, trimmed, ends with `,`, `:`, `->` or one of
     *    [CONTINUATION_WORDS] and holds no `{`, `}` or `;` (a Rust `where` clause, `implements A,` above `B {`;
     *    not `},` between enum constants). Stop without prepending when there is no previous line,
     *    or the previous line is blank in [masked], or its masked text, trimmed, ends with `;`, `{` or `}`.
     *    If the previous line holds [floor], prepend only its part after [floor], then stop.
     * 3. text = the collected masked text with every whitespace run replaced by one space, trimmed.
     */
    fun of(masked: String, lines: Lines, open: Int, floor: Int): Header {
        val line = lines.lineOf(open)
        val lineStart = lines.start(line)

        // 1. The last boundary on this line, at bracket depth 0 counted from the line start.
        var depth = 0
        var boundary = -1
        for (k in lineStart until open) {
            when (masked[k]) {
                '(', '[' -> depth++
                ')', ']' -> depth--
                ';', '{', '}' -> if (depth == 0) boundary = k
            }
        }
        var start = maxOf(if (boundary >= 0) boundary + 1 else lineStart, floor + 1)

        // 2. Extend upward, only when the header starts at the line start.
        if (start == lineStart) {
            while (true) {
                val prev = lines.lineOf(start) - 1
                if (prev < 0 || lines.isBlank(prev, masked)) break
                val prevText = masked.substring(lines.start(prev), lines.end(prev)).trim()
                if (!needsMore(masked, start, open) && !endsOpen(prevText)) break
                if (prevText.endsWith(';') || prevText.endsWith('{') || prevText.endsWith('}')) break
                if (lines.start(prev) <= floor) {
                    start = floor + 1
                    break
                }
                start = lines.start(prev)
            }
        }

        // 3. Normalize.
        val text = masked.substring(start, open).replace(WHITESPACE, " ").trim()
        var first = start
        while (first < open && masked[first].isWhitespace()) first++
        return Header(text, if (text.isEmpty()) open else first)
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * A line that leaves the header open: it ends with `,`, `:`, `->` or a continuation word (`where`,
     * `implements`…), and holds no `{`, `}` or `;` (`},` between two enum constants closes a block).
     */
    private fun endsOpen(t: String): Boolean =
        t.none { it == '{' || it == '}' || it == ';' } &&
            (t.endsWith(",") || t.endsWith(":") || t.endsWith("->") ||
                CONTINUATION_WORDS.any { w -> t == w || (t.endsWith(w) && !t[t.length - w.length - 1].isLetterOrDigit()) })

    /** Blank, more closers than openers, or starting with a continuation (`:`, `->`, `,`, `where`…). */
    private fun needsMore(masked: String, start: Int, open: Int): Boolean {
        var balance = 0
        var blank = true
        for (k in start until open) {
            when (masked[k]) {
                '(', '[' -> balance++
                ')', ']' -> balance--
            }
            if (!masked[k].isWhitespace()) blank = false
        }
        if (blank || balance < 0) return true
        val t = masked.substring(start, open).trim()
        if (t.startsWith(":") || t.startsWith("->") || t.startsWith(",")) return true
        return CONTINUATION_WORDS.any { w -> t.startsWith(w) && (t.length == w.length || !t[w.length].isLetterOrDigit()) }
    }
}
