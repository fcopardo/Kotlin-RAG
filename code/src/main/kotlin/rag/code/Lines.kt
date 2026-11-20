package rag.code

/**
 * Line arithmetic over a text (plan step 2, part C1). Lines are 0-based. A line's `'\n'` belongs to
 * it; a text ending with `'\n'` has one more, empty, line after it, so there is always at least one
 * line. Built once in linear time; [lineOf] is a binary search.
 *
 * Methods taking `s` work on any string of the same length as the text: the original or the masked one.
 */
internal class Lines(private val text: String) {
    private val starts: IntArray
    
    init {
        // Build the starts array in one pass
        val startList = mutableListOf<Int>()
        startList.add(0) // First line starts at offset 0
        
        for (i in text.indices) {
            if (text[i] == '\n') {
                startList.add(i + 1) // Next line starts after the newline
            }
        }
        
        starts = startList.toIntArray()
    }

    /** Number of lines: 1 + the number of `'\n'`. */
    val count: Int get() = starts.size

    /** The line holding [offset]; `offset == text.length` is on the last line. */
    fun lineOf(offset: Int): Int {
        // Binary search for the last start <= offset
        val index = starts.binarySearch(offset)
        return if (index >= 0) index else -index - 2
    }

    /** Offset of the line's first character. */
    fun start(line: Int): Int = starts[line]

    /** Offset just past the line's content: its `'\n'`, or `text.length` for the last line. */
    fun end(line: Int): Int {
        val nextStart = if (line + 1 < starts.size) starts[line + 1] else text.length
        // The end is the position just past the line's content
        // If there's a next line, it starts at nextStart, so this line ends at nextStart - 1
        // If this is the last line and text doesn't end with newline, it ends at text.length
        return if (line + 1 < starts.size) nextStart - 1 else text.length
    }

    /** Number of leading spaces and tabs (a tab counts 1) in the original text. */
    fun indent(line: Int): Int {
        val start = start(line)
        val end = end(line)
        var indent = 0
        for (i in start until end) {
            val c = text[i]
            if (c == ' ' || c == '\t') {
                indent++
            } else {
                break
            }
        }
        return indent
    }

    /** Offset of the first non-whitespace character of the line in [s], or -1 if there is none. */
    fun firstNonBlank(line: Int, s: String): Int {
        val start = start(line)
        val end = end(line)
        for (i in start until end) {
            if (!s[i].isWhitespace()) {
                return i
            }
        }
        return -1
    }

    /** True when the line holds only whitespace in [s]. */
    fun isBlank(line: Int, s: String): Boolean {
        val start = start(line)
        val end = end(line)
        for (i in start until end) {
            if (!s[i].isWhitespace()) {
                return false
            }
        }
        return true
    }
}
