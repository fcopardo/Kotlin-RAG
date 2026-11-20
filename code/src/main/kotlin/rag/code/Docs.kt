package rag.code

internal object Docs {
    /**
     * Where a declaration really starts once the doc comments and annotations directly above it are
     * included (plan step 2, part C4). [start] = the declaration's own first character. [floor] = the
     * last offset that must stay outside it (the parent's `{`, or the end of the previous declaration in
     * the same region), or -1.
     *
     * From the line of [start], look at the line above, repeatedly. Stop when: there is none; it holds
     * [floor] or anything before it (its start ≤ [floor]); it is blank in [original]; or it is neither a
     * comment line (blank in [masked], not blank in [original]) nor an annotation line (its [original]
     * text, trimmed, starts with one of [prefixes]). Otherwise it becomes part of the declaration.
     * Returns the first non-blank offset (in [original]) of the topmost line taken, or [start].
     */
    fun start(masked: String, original: String, lines: Lines, start: Int, floor: Int, prefixes: List<String>): Int {
        var line = lines.lineOf(start)
        var top = start
        
        while (line > 0) {
            val l = line - 1
            if (lines.start(l) <= floor) break
            if (lines.isBlank(l, original)) break
            val comment = lines.isBlank(l, masked)
            val annotation = original.substring(lines.start(l), lines.end(l)).trim().let { t -> prefixes.any { t.startsWith(it) } }
            if (!comment && !annotation) break
            top = lines.firstNonBlank(l, original)
            line = l
        }
        
        return top
    }
}
