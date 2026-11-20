package rag.code

/** A function without braces: [start] = its first character, [end] = its last, inclusive. */
internal data class BodylessFunction(val name: String, val start: Int, val end: Int)

internal object Bodyless {
    /**
     * The functions without braces declared directly in the region `[from, to)` (plan step 2, part C5).
     * [children] = the region's child blocks, sorted by `open` and disjoint; code inside them is not in
     * the region. Result sorted by start.
     *
     * For each line L of the region, in order:
     * 1. `first` = the first offset of L within `[from, to)` that is non-whitespace in [masked]. Skip L
     *    if there is none, or if `first` is inside a child block (`open <= first <= close`).
     * 2. The statement: [masked] from `first` to the end of L, extended over the following lines (never
     *    reaching `to`) while it has more `(`+`[` than `)`+`]`. Normalize it like a header (whitespace
     *    runs → one space, trimmed). If `rules.bodylessFunction(statement)` is null, go to the next line.
     * 3. Otherwise it is a function named so, starting at `first`. I = indent of L. Look at the lines after
     *    the statement's last line (lines blank in [original] are passed over): the function stops at the first one whose first non-blank character
     *    in [original], `fo`, is at or after `to`, OR that is not blank in [original], has indent ≤ I, and
     *    whose `fo` is not inside a child block opened at or after `first` (`first <= open < fo <= close`).
     *    With no such line it stops at `to`.
     * 4. Its end = the last offset before the stop point (and before `to`) that is non-whitespace in
 *    [uncommented] (code or a literal, not a comment). [uncommented] defaults to [masked].
     * 5. Continue with the first line after the one holding its end.
     *
     * Child block lookups must be binary searches over [children] (they are sorted and disjoint), so a
     * region with 20,000 blocks stays linear-ish.
     */
    fun find(masked: String, original: String, lines: Lines, from: Int, to: Int, children: List<Block>, rules: FamilyRules, uncommented: String = masked): List<BodylessFunction> {
        val result = mutableListOf<BodylessFunction>()
        var line = lines.lineOf(from)
        while (line < lines.count && lines.start(line) < to) {
            // 1. The line's first code character in the region, outside child blocks.
            val first = firstCode(masked, maxOf(from, lines.start(line)), minOf(lines.end(line), to))
            if (first < 0 || childAt(children, first) != null) {
                line++
                continue
            }
            // 2. The statement, over more lines while brackets are open.
            var endLine = line
            while (balance(masked, first, minOf(lines.end(endLine), to)) > 0 &&
                endLine + 1 < lines.count && lines.start(endLine + 1) < to
            ) endLine++
            val statement = masked.substring(first, minOf(lines.end(endLine), to)).replace(WHITESPACE, " ").trim()
            val name = rules.bodylessFunction(statement)
            if (name == null) {
                line++
                continue
            }
            // 3. Where it stops.
            val indent = lines.indent(line)
            var stop = to
            var l = endLine + 1
            while (l < lines.count && lines.start(l) < to) {
                if (!lines.isBlank(l, original)) {
                    val fo = lines.firstNonBlank(l, original)
                    if (fo >= to) {
                        stop = lines.start(l)
                        break
                    }
                    val opened = childAt(children, fo)?.takeIf { it.open >= first }
                    if (lines.indent(l) <= indent && opened == null) {
                        stop = lines.start(l)
                        break
                    }
                }
                l++
            }
            // 4. Its last code character before the stop.
            var end = minOf(stop, to) - 1
            while (end > first && uncommented[end].isWhitespace()) end--
            result += BodylessFunction(name, first, end)
            // 5. Go on after it.
            line = lines.lineOf(end) + 1
        }
        return result
    }

    private val WHITESPACE = Regex("\\s+")

    private fun firstCode(masked: String, from: Int, to: Int): Int {
        for (k in from until to) if (!masked[k].isWhitespace()) return k
        return -1
    }

    /** `(`+`[` minus `)`+`]` in `masked[from, to)`. */
    private fun balance(masked: String, from: Int, to: Int): Int {
        var b = 0
        for (k in from until to) when (masked[k]) {
            '(', '[' -> b++
            ')', ']' -> b--
        }
        return b
    }

    /** The child block with `open <= offset <= close`, by binary search ([children] are sorted and disjoint). */
    private fun childAt(children: List<Block>, offset: Int): Block? {
        var lo = 0
        var hi = children.size - 1
        var candidate = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (children[mid].open <= offset) {
                candidate = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return if (candidate >= 0 && offset <= children[candidate].close) children[candidate] else null
    }
}
