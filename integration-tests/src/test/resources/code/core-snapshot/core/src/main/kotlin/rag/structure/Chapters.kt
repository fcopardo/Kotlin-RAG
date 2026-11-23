package rag.structure

import rag.extract.ExtractedBook

/** A chapter's span in the book text. [charEnd] is inclusive. Chapter 0 is front matter. */
data class Chapter(
    val number: Int,
    val title: String,
    val charStart: Int,
    val charEnd: Int,
    val pageStart: Int,
    val pageEnd: Int,
)

/** Turns chapter start pages into char ranges of the assembled book text (SPEC §5.3). */
object Chapters {

    const val FRONT_MATTER = "Front matter"

    fun compute(book: ExtractedBook, starts: List<ChapterStart>): List<Chapter> {
        val text = book.text
        if (text.isEmpty()) return emptyList()

        // A chapter starts at its title line, or at the top of its page if the line isn't there.
        val offsets = mutableListOf<Pair<String, Int>>()
        starts.forEach { start ->
            val page = start.pdfPage.coerceIn(1, book.pageCount)
            val from = book.pageStarts[page - 1]
            val until = if (page < book.pageCount) book.pageStarts[page] else text.length
            // EPUB knows the exact offset; a PDF chapter ("Part › Chapter") is found by its own heading.
            val offset = start.charOffset?.coerceIn(0, text.length)
                ?: TitleMatcher.findLine(text, OutlineChapters.ownTitle(start.title), from, until) ?: from
            val previous = offsets.lastOrNull()?.second ?: -1
            when {
                offset >= text.length -> Unit
                offset > previous -> offsets += start.title to offset
                // Same position (e.g. a part whose intro page has no text, then its first chapter):
                // the later, more specific start wins.
                offset == previous -> offsets[offsets.size - 1] = start.title to offset
            }
        }
        if (offsets.isEmpty()) offsets += "Book" to 0

        val chapters = mutableListOf<Chapter>()
        val firstOffset = offsets.first().second
        if (firstOffset > 0 && text.substring(0, firstOffset).isNotBlank()) {
            chapters += chapter(book, 0, FRONT_MATTER, 0, firstOffset - 1)
        } else if (firstOffset > 0) {
            offsets[0] = offsets[0].first to 0 // only whitespace before: let chapter 1 absorb it
        }
        offsets.forEachIndexed { i, (title, offset) ->
            val end = if (i + 1 < offsets.size) offsets[i + 1].second - 1 else text.length - 1
            chapters += chapter(book, i + 1, title, offset, end)
        }
        return chapters
    }

    private fun chapter(book: ExtractedBook, number: Int, title: String, start: Int, end: Int) =
        Chapter(number, title, start, end, book.pageOf(start), book.pageOf(end))
}
