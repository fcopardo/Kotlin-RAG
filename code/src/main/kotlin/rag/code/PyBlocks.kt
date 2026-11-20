package rag.code

/**
 * A Python compound statement and its body (plan step 2g). Offsets are into the original text.
 *
 * @property start first non-blank character of its first decorator line (`@…` directly above it), or [header].
 * @property header first non-blank character of the header line (`def`, `class`, `if`, `async`…).
 * @property colon offset of the `:` that ends the header.
 * @property indent indentation of the header line (see [PyBlocks.build]).
 * @property end last character of the block, inclusive: the last character of its last body line that is
 *   non-blank in [Masked.uncommented] (so a trailing comment is not part of it), or of the header line when
 *   the body is on the same line (`def f(): return 1`) or missing.
 * @property children the compound statements directly in its body, in order.
 */
internal class PyBlock(
    val start: Int,
    val header: Int,
    val colon: Int,
    val indent: Int,
    var end: Int,
    val children: MutableList<PyBlock> = mutableListOf(),
)

internal object PyBlocks {
    /** First words of the lines that open a block. */
    val COMPOUND = setOf(
        "class", "def", "async", "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "match", "case",
    )

    private class Logical(val first: Int, var last: Int, val indent: Int)

    private fun indentOf(text: String, lines: Lines, l: Int): Int {
        var col = 0
        for (i in lines.start(l) until lines.end(l)) {
            when (text[i]) { ' ' -> col++; '\t' -> col = (col / 8 + 1) * 8; else -> return col }
        }
        return col
    }

    /** Last offset in [from, to) that is non-blank in s, or -1. */
    private fun lastNonBlank(s: String, from: Int, to: Int): Int {
        var i = to - 1
        while (i >= from) { if (!s[i].isWhitespace()) return i; i-- }
        return -1
    }

    // Step 1: logical lines, in one pass.
    private fun logicalLines(m: Masked, text: String, lines: Lines): List<Logical> {
        val M = m.text; val U = m.uncommented
        val out = mutableListOf<Logical>()
        var cur: Logical? = null
        var depth = 0
        for (l in 0 until lines.count) {
            val continues = cur != null && l > 0 && (
                depth > 0 ||
                M.substring(lines.start(l - 1), lines.end(l - 1)).trimEnd().endsWith("\\") ||
                (lines.end(l - 1) < text.length && m.breakInLiteral(lines.end(l - 1))))
            if (!continues) {
                cur = null
                depth = 0
                if (!lines.isBlank(l, U)) {
                    cur = Logical(lines.firstNonBlank(l, U), -1, indentOf(text, lines, l))
                    out += cur
                }
            }
            cur?.let { c ->
                val e = lastNonBlank(U, lines.start(l), lines.end(l))
                if (e > c.last) c.last = e
            }
            for (i in lines.start(l) until lines.end(l)) when (M[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
        }
        return out
    }

    // Step 2: the colon of an opener, or -1.
    private fun colonOf(M: String, ll: Logical): Int {
        var e = ll.first
        while (e < M.length && (M[e].isLetterOrDigit() || M[e] == '_')) e++
        if (M.substring(ll.first, e) !in COMPOUND) return -1
        var depth = 0
        for (i in ll.first..ll.last) when (M[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            ':' -> if (depth == 0 && !(i + 1 < M.length && M[i + 1] == '=')) return i
        }
        return -1
    }

    /**
     * Part 2g-1. The top-level blocks of a Python file; linear time, never throws. M = `masked.text`,
     * U = `masked.uncommented` (comments blank, strings kept).
     *
     * 1. **Logical lines.** Walk the physical lines in order, keeping a bracket depth (`(` `[` `{` add 1,
     *    `)` `]` `}` subtract 1, never below 0; counted in M). A physical line L > 0 CONTINUES the current
     *    logical line when one is open and: the depth is above 0 after line L-1, or line L-1 in M, with
     *    trailing whitespace dropped, ends with `\`, or `masked.breakInLiteral(lines.end(L - 1))` (L starts
     *    inside a multi-line string). Otherwise, a line blank in U is skipped (it starts nothing; the open
     *    logical line, if any, ends), and any other line starts a new logical line.
     *    A logical line has: `first` = the first non-blank offset of its first physical line in U; `last` =
     *    the last offset non-blank in U over all its physical lines; `indent` = the leading whitespace of its
     *    first physical line in the original text, a space adding 1 and a tab moving to the next multiple of 8.
     * 2. **Openers.** A logical line opens a block when its first word in M (letters, digits, `_` from
     *    `first`) is in [COMPOUND] and it has a `:` in M, between `first` and `last`, at depth 0 counted
     *    from `first`, and not followed by `=` (`:=` is not one). `colon` = the first such `:`. The body is
     *    INLINE when U has a non-blank character after `colon` up to `last`.
     * 3. **Decorators.** A logical line whose character at `first` in M is `@` is a decorator: `pending` =
     *    its `first` if no decorator is pending. Every other logical line clears `pending` after use.
     * 4. **Nesting.** A stack of open blocks; `prevLast` = `last` of the previous logical line (-1 at first).
     *    For each logical line: first pop every block whose `indent` ≥ this line's `indent`, setting its
     *    `end` = `prevLast`. Then if it is a decorator, see 3. Else, if it opens a block, create
     *    `PyBlock(start = pending, or first when none is pending; header = first; colon; indent; end = last)`,
     *    add it to the children of the stack's top (or to the result when the stack is empty), and push it
     *    unless its body is inline; then clear `pending`. Then `prevLast = last`.
     *    At the end, pop every block left, setting `end` = `prevLast`.
     */
    fun build(masked: Masked, lines: Lines): List<PyBlock> {
        val text = masked.uncommented   // same length and spacing as the original
        val M = masked.text; val U = masked.uncommented
        val roots = mutableListOf<PyBlock>()
        val stack = ArrayDeque<PyBlock>()
        var pending = -1
        var prevLast = -1
        for (ll in logicalLines(masked, text, lines)) {
            while (stack.isNotEmpty() && stack.last().indent >= ll.indent) stack.removeLast().end = prevLast
            if (M[ll.first] == '@') {
                if (pending < 0) pending = ll.first
            } else {
                val c = colonOf(M, ll)
                if (c >= 0) {
                    val b = PyBlock(if (pending >= 0) pending else ll.first, ll.first, c, ll.indent, ll.last)
                    (if (stack.isEmpty()) roots else stack.last().children).add(b)
                    val inline = lastNonBlank(U, c + 1, ll.last + 1) >= 0
                    if (!inline) stack.addLast(b)
                }
                pending = -1
            }
            prevLast = ll.last
        }
        while (stack.isNotEmpty()) stack.removeLast().end = prevLast
        return roots
    }
}
