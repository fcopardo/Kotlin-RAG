package rag.code

import rag.BookFormat
import rag.DocumentKinds
import rag.PageKind
import rag.RagException
import rag.ReadOptions
import rag.StructuredBook
import rag.extract.DocumentCharset
import rag.extract.ExtractedBook
import rag.extract.Normalizer
import rag.store.ProjectFile
import rag.structure.ChapterPlan
import rag.structure.ChapterStrategy
import rag.structure.Chapters
import rag.structure.Segmenter
import java.security.MessageDigest
import java.util.Locale

/** A file of a project: its relative `/`-separated [path] and its raw [bytes]. */
class SourceFile(val path: String, val bytes: ByteArray)

/**
 * Builds a code project (SPEC §18.2, §18.4; plan step 3): project › file and class (`c`) › function (`g`,
 * one paragraph block per function, split only when over the token cap) › line (`p`). One page per file,
 * kind [PageKind.FILE], labelled with its relative path. A single file is a project of one.
 */
object CodeBookBuilder {
    /** Bump whenever the scan's heuristics change, so `--reindex` picks the change up. */
    const val EXTRACTOR = "code/scan-v1"

    /**
     * Part 3c. Files are taken in [SourceFile.path] order. For each file:
     * - `t` = [Normalizer.normalizeSource] of [DocumentCharset.decode] of its bytes; a blank `t` is skipped
     *   (no page, no [ProjectFile]).
     * - Append `t` to the book text, preceded by `"\n\n"` unless it is the first; `start`/`end` (inclusive)
     *   = its range. Its page start is `start` and its page label its path.
     * - Declarations = [DeclarationFinder.find] of `t` with [Language.of] its extension (the text after the
     *   last `.` of the file name; none → [Language.OTHER]), each shifted by `start`. Warnings become
     *   `"<path>: <warning>"`.
     * - Its FUNCTION declarations become paragraph blocks `start..end`; a block that starts at or before the
     *   end of the previous kept block is dropped (blocks must not overlap).
     * - `start..end` is a code range (lines are phrases); a [ProjectFile] gets its path, the lowercase hex
     *   SHA-256 of its raw bytes, `start` and `end`.
     * No file with text → [RagException.BookUnreadable] (`"No source text in <name>"`).
     * Chapters: [CodeChapters.starts], plan strategy [ChapterStrategy.CODE] with the warnings,
     * [Chapters.compute], then `Segmenter(options.locale ?: Locale.ENGLISH).segment`. The book: title
     * [name], format [BookFormat.CODE], page kind [PageKind.FILE], extractor [EXTRACTOR], kind
     * `DocumentKinds.CODE.name`, and the project files.
     */
    fun build(name: String, files: List<SourceFile>, options: ReadOptions = ReadOptions()): StructuredBook {
        val sb = StringBuilder()
        val pageStarts = mutableListOf<Int>()
        val labels = mutableListOf<String>()
        val codeRanges = mutableListOf<IntRange>()
        val blocks = mutableListOf<IntRange>()
        val scanned = mutableListOf<ScannedFile>()
        val projectFiles = mutableListOf<ProjectFile>()
        val warnings = mutableListOf<String>()
        
        for (f in files.sortedBy { it.path }) {
            val t = Normalizer.normalizeSource(DocumentCharset.decode(f.bytes))
            if (t.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            val start = sb.length
            sb.append(t)
            val end = sb.length - 1
            val fileName = f.path.substringAfterLast('/')
            val ext = if ('.' in fileName) fileName.substringAfterLast('.') else ""
            val found = DeclarationFinder.find(t, Language.of(ext))
            warnings += found.warnings.map { "${f.path}: $it" }
            val decls = found.items.map { it.copy(start = it.start + start, end = it.end + start) }
            for (d in decls) {
                if (d.kind != DeclarationKind.FUNCTION) continue
                if (blocks.isNotEmpty() && d.start <= blocks.last().last) continue
                blocks += d.start..d.end
            }
            pageStarts += start
            labels += f.path
            codeRanges += start..end
            scanned += ScannedFile(f.path, start, end, pageStarts.size, SourceTitles.of(f.path), decls)
            projectFiles += ProjectFile(f.path, sha256(f.bytes), start, end)
        }
        
        if (sb.isEmpty()) throw RagException.BookUnreadable("No source text in $name")
        val text = sb.toString()
        val book = ExtractedBook(text, pageStarts.toIntArray(), codeRanges, labels, blocks)
        val starts = CodeChapters.starts(text, scanned)
        val plan = ChapterPlan(ChapterStrategy.CODE, starts, warnings = warnings)
        val chapters = Chapters.compute(book, starts)
        val segments = Segmenter(options.locale ?: Locale.ENGLISH).segment(book, chapters)
        return StructuredBook(
            book, chapters, plan, segments,
            title = name, format = BookFormat.CODE, pageKind = PageKind.FILE, extractor = EXTRACTOR,
            kind = DocumentKinds.CODE.name, projectFiles = projectFiles,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
