package rag.code

/**
 * A `{ … }` block of masked source text (plan step 2, part C2). [open] and [close] are the offsets of
 * its braces. [inParens]: the `{` was opened while more `(` than `)` were open, so it can't be a body.
 */
internal class Block(val open: Int, val inParens: Boolean, val parent: Block?) {
    var close: Int = -1
    val children = mutableListOf<Block>()
}

internal object BlockTree {
    /**
     * The top-level blocks of [masked], each with its nested [Block.children], in order of [Block.open].
     *
     * One left-to-right pass with an explicit stack (no recursion: 10,000 levels must work).
     * - Count `(` minus `)`, never below 0. A `{` opens a block with `inParens = count > 0`, appended to
     *   the children of the innermost open block (or to the result), and pushed.
     * - A `}` closes the innermost open block. With none open it is ignored and adds the warning
     *   `"line N: unmatched }"`, N = its 1-based line.
     * - At the end, every block still open gets `close` = offset of the last non-whitespace character of
     *   [original], and adds `"line N: unclosed {"`, N = the 1-based line of its `{`; these warnings come in
     *   order of `{` offset, after the unmatched ones found during the pass.
     */
    fun build(masked: String, original: String, lines: Lines, warnings: MutableList<String>): List<Block> {
        val result = mutableListOf<Block>()
        val stack = ArrayDeque<Block>()
        var parens = 0
        
        for (i in masked.indices) {
            when (masked[i]) {
                '(' -> parens++
                ')' -> if (parens > 0) parens--
                '{' -> { 
                    val b = Block(i, parens > 0, stack.lastOrNull())
                    (stack.lastOrNull()?.children ?: result).add(b)
                    stack.addLast(b)
                }
                '}' -> {
                    if (stack.isEmpty()) {
                        warnings += "line ${lines.lineOf(i) + 1}: unmatched }"
                    } else {
                        stack.removeLast().close = i
                    }
                }
            }
        }
        
        // Handle unclosed blocks - add warnings in order of opening (stack bottom first)
        val lastNonWhitespace = findLastNonWhitespace(original)
        val unclosedWarnings = mutableListOf<String>()
        while (stack.isNotEmpty()) {
            val block = stack.removeLast()
            block.close = lastNonWhitespace
            unclosedWarnings += "line ${lines.lineOf(block.open) + 1}: unclosed {"
        }
        // Add warnings in reverse order (since we processed stack in LIFO but want FIFO)
        warnings.addAll(unclosedWarnings.reversed())
        
        return result
    }
    
    private fun findLastNonWhitespace(s: String): Int {
        for (i in s.indices.reversed()) {
            if (!s[i].isWhitespace()) {
                return i
            }
        }
        return -1
    }
}
