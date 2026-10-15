package rag.extract

/**
 * Removes footnote noise from one page's raw text, before NFKC turns superscripts into plain
 * digits ("Birch¹" would become "Birch1"). Pure.
 *
 * - A footnote is a line starting with superscript digits ("²https://cs.android.com/").
 * - A footnote whose content is only a URL is removed, with the no-space lines right after it
 *   that look like more URL (a long URL wraps). Footnotes with prose are kept, without their marker.
 * - In the body, superscript digits are removed only when that number is a footnote on the same
 *   page, so real superscripts (n², 10³) stay.
 */
object Footnotes {

    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
    private val footnoteLine = Regex("^\\s*([$SUPERSCRIPTS]+)\\s*(.*)$")
    private val marker = Regex("(?<=\\S)([$SUPERSCRIPTS]+)")
    private val url = Regex("^(https?://|www\\.)\\S*$", RegexOption.IGNORE_CASE)
    /** Rest of a wrapped URL: no spaces, has URL punctuation, and isn't just a number. */
    private val urlContinuation = Regex("^(?!\\d+$)\\S*[/._\\-=?&#%:]\\S*$")

    fun clean(page: String): String {
        val lines = page.split('\n')
        val out = mutableListOf<String>()
        val numbers = mutableSetOf<String>()
        var i = 0
        while (i < lines.size) {
            val m = footnoteLine.matchEntire(lines[i])
            if (m == null) {
                out += lines[i]
                i++
                continue
            }
            numbers += m.groupValues[1]
            val content = m.groupValues[2].trim()
            i++
            if (url.matches(content)) {
                // A wrapped URL continues on lines without spaces that aren't footnotes themselves.
                while (i < lines.size && urlContinuation.matches(lines[i].trim()) && footnoteLine.matchEntire(lines[i]) == null) i++
            } else {
                out += content
            }
        }
        if (numbers.isEmpty()) return page
        return out.joinToString("\n") { line -> marker.replace(line) { if (it.value in numbers) "" else it.value } }
    }
}
