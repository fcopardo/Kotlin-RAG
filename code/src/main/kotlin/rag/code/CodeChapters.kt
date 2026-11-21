package rag.code

import rag.structure.ChapterStart

/**
 * One file of a project as laid out in the book text (offsets are into the BOOK text).
 *
 * @property start offset of its first character; @property end offset of its last character (inclusive).
 * @property page its 1-based page (one page per file). @property titles its [SourceTitles] parts.
 * @property declarations its declarations, already shifted to book offsets, sorted by start.
 */
internal class ScannedFile(
    val path: String,
    val start: Int,
    val end: Int,
    val page: Int,
    val titles: List<String>,
    val declarations: List<Declaration>,
)

/**
 * Where the chapters (the `c` level, SPEC §18.2) of a code project start (plan step 3b): at each file,
 * at each class, and again after a class where the code of the enclosing class or file goes on, so
 * that code is never attributed to the class before it.
 */
internal object CodeChapters {
    const val SEPARATOR = " › "

    /**
     * Part 3b. For each file f, in order, with T = `f.titles` joined by [SEPARATOR]:
     * 1. Emit `(T, f.start)`.
     * 2. Walk the CLASS declarations of f (functions never make chapters) in order, with a stack of
     *    open classes. For each class c: while the stack is not empty and its top's `end` < `c.start`,
     *    pop it and emit a "back" start at `top.end + 1`, titled for what is open after the pop (see
     *    titles below). Then emit `(title(c), c.start)` and push c.
     * 3. After the last class, pop every class left the same way, emitting a back start for each.
     * Titles: a class's title is T + the names of the classes on the stack below it + its own name, all
     * joined by [SEPARATOR] (`core › rag › A.kt › Outer › Inner`); a back start's title is the title of
     * the class now on top of the stack, or T when it is empty.
     * Every start has `pdfPage = f.page` and `charOffset` set.
     * 4. Finally, drop every start whose range is EMPTY in [text] (holds nothing but whitespace and the
     *    closing characters `}` `)` `]` `;` `,`): its range runs from its offset
     *    to just before the next KEPT-OR-NOT start's offset in the emitted list (or to `text.length`).
     *    Decide every start against the original emitted list, then drop. (So a file that opens with a
     *    class loses its file start; a back start right before the next class or at the end of the file
     *    disappears.)
     */
    fun starts(text: String, files: List<ScannedFile>): List<ChapterStart> {
        val emitted = mutableListOf<ChapterStart>()
        for (f in files) {
            val fileTitle = f.titles.joinToString(SEPARATOR)
            val stack = ArrayList<Pair<Declaration, String>>()        // open class, its title
            fun topTitle() = if (stack.isEmpty()) fileTitle else stack.last().second
            fun pop() {
                val (c, _) = stack.removeAt(stack.size - 1)
                emitted += ChapterStart(topTitle(), f.page, c.end + 1)
            }
            emitted += ChapterStart(fileTitle, f.page, f.start)
            for (c in f.declarations) {
                if (c.kind != DeclarationKind.CLASS) continue
                while (stack.isNotEmpty() && stack.last().first.end < c.start) pop()
                val title = topTitle() + SEPARATOR + c.name
                emitted += ChapterStart(title, f.page, c.start)
                stack += c to title
            }
            while (stack.isNotEmpty()) pop()
        }
        // Step 4: decide against the emitted list, then drop.
        return emitted.filterIndexed { i, s ->
            val from = s.charOffset!!
            val to = if (i + 1 < emitted.size) emitted[i + 1].charOffset!! else text.length
            hasContent(text, from, to)
        }
    }

    /** true when text[from, to) holds a character other than whitespace and } ) ] ; , */
    private fun hasContent(text: String, from: Int, to: Int): Boolean {
        for (k in from until minOf(to, text.length)) {
            val ch = text[k]
            if (!ch.isWhitespace() && ch !in "})];,") return true
        }
        return false
    }
}
