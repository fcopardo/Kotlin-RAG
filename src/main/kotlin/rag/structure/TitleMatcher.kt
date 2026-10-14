package rag.structure

import rag.extract.Normalizer

/** Finds a title as a whole line (or two consecutive lines, for wrapped titles) in text. */
object TitleMatcher {

    /** Char offset of the line that matches [title] within `text[from, until)`, or null. */
    fun findLine(text: String, title: String, from: Int = 0, until: Int = text.length): Int? {
        val target = Normalizer.hashNormalize(title)
        if (target.isEmpty()) return null

        val lines = mutableListOf<Pair<Int, String>>() // (start offset, hash-normalized line)
        var start = from
        while (start < until) {
            val end = text.indexOf('\n', start).let { if (it == -1 || it > until) until else it }
            val line = Normalizer.hashNormalize(text.substring(start, end))
            if (line.isNotEmpty()) lines += start to line
            start = end + 1
        }
        lines.forEachIndexed { i, (offset, line) ->
            if (line == target) return offset
            if (i + 1 < lines.size && "$line ${lines[i + 1].second}" == target) return offset
        }
        return null
    }

    fun containsLine(text: String, title: String): Boolean = findLine(text, title) != null
}
