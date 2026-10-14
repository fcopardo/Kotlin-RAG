package rag.extract

/**
 * Finds numbered code listings in a page's normalized text and turns each into one block.
 *
 * Books often print listings with line numbers ("1 @Composable", "2 fun Foo() {"), and PDF
 * extraction puts a blank line after every code line. A run of at least [minLines] lines numbered
 * n, n+1, n+2… (blank lines between them allowed) is a listing. The numbers are removed, the
 * blank lines between code lines are dropped, and the block gets a blank line on both sides so it
 * becomes its own paragraph. A wrapped code line (up to [maxContinuation] unnumbered lines before
 * the next expected number) stays in the block.
 *
 * Pure: works on one page's text.
 */
class CodeListings(private val minLines: Int = 2, private val maxContinuation: Int = 2) {

    /** [text] has no leading or trailing newlines; [codeRanges] are inclusive char ranges in it. */
    data class Result(val text: String, val codeRanges: List<IntRange>)

    private val numbered = Regex("^(\\d{1,4})(?: (.*))?$")

    fun clean(pageText: String): Result {
        val lines = pageText.split('\n')
        val out = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            val run = runAt(lines, i)
            if (run != null) {
                if (out.isNotEmpty()) blankLineBefore(out)
                val start = out.length
                out.append(run.code.joinToString("\n"))
                ranges += start until out.length
                out.append("\n\n")
                i = run.end
            } else {
                val line = lines[i]
                // Skip leading blank lines and never write more than one blank line in a row.
                if (line.isNotBlank() || (out.isNotEmpty() && !out.endsWith("\n\n"))) out.append(line).append('\n')
                i++
            }
        }
        return Result(out.trimEnd('\n').toString(), ranges)
    }

    private class Run(val code: List<String>, val end: Int)

    private fun runAt(lines: List<String>, start: Int): Run? {
        var expected = number(lines[start]) ?: return null
        val code = mutableListOf<String>()
        var numberedLines = 0
        var i = start
        var end = start
        while (i < lines.size) {
            val n = number(lines[i])
            if (n == expected) {
                content(lines[i]).takeIf { it.isNotBlank() }?.let(code::add)
                numberedLines++
                expected++
                end = i + 1
                i++
                continue
            }
            if (lines[i].isBlank()) { i++; continue }
            // Unnumbered lines are a wrapped code line only if the next number follows soon.
            val next = (i until minOf(lines.size, i + maxContinuation * 2 + 1))
                .firstOrNull { number(lines[it]) == expected } ?: break
            val wrapped = (i until next).map { lines[it] }.filter { it.isNotBlank() }
            if (wrapped.size > maxContinuation) break
            code += wrapped
            i = next
        }
        return if (numberedLines >= minLines) Run(code, end) else null
    }

    private fun number(line: String): Int? = numbered.matchEntire(line.trim())?.groupValues?.get(1)?.toInt()

    private fun content(line: String): String = numbered.matchEntire(line.trim())!!.groupValues[2]

    private fun blankLineBefore(out: StringBuilder) {
        if (!out.endsWith("\n")) out.append('\n')
        if (!out.endsWith("\n\n")) out.append('\n')
    }
}
