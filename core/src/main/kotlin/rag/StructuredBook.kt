package rag

import rag.extract.ExtractedBook
import rag.store.ProjectFile
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
    /** Subjects the file declares (EPUB `dc:subject`, PDF Keywords), as written (SPEC §8.5). */
    val subjects: List<String> = emptyList(),
    /** The kind of document, which decides what its levels are called (SPEC §18.2). */
    val kind: String = DocumentKinds.BOOK.name,
    /** A code project's files, in text order, for the `project_file` table (SPEC §18.4); empty for documents. */
    val projectFiles: List<ProjectFile> = emptyList(),
    /** What a code project's reader left out, as `"<path>: <reason>"`, sorted by path; empty for documents. */
    val skippedFiles: List<String> = emptyList(),
) {
    fun text(chapter: Chapter): String = book.text.substring(chapter.charStart, chapter.charEnd + 1)

    fun text(start: Int, end: Int): String = book.text.substring(start, end + 1)
}
