package rag

import rag.extract.ExtractedBook
import rag.structure.Chapter
import rag.structure.ChapterPlan
import rag.structure.SegmentedChapter

/** A book's text with its chapters, paragraphs and phrases located in it. */
class StructuredBook(
    val book: ExtractedBook,
    val chapters: List<Chapter>,
    val plan: ChapterPlan,
    val segments: List<SegmentedChapter>,
    val title: String? = null,
    val format: BookFormat = BookFormat.PDF,
    val pageKind: PageKind = PageKind.PDF,
    /** What produced the text (see [BookSource.extractor]). */
    val extractor: String = "unknown",
) {
    fun text(chapter: Chapter): String = book.text.substring(chapter.charStart, chapter.charEnd + 1)

    fun text(start: Int, end: Int): String = book.text.substring(start, end + 1)
}
