package rag

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rag.structure.Segmenter

/** One stored passage (`g` unit), or one function split into several (SPEC §18.4, plan step 5). */
@Serializable
data class StructureUnit(val where: String, val chars: Int, val tokens: Int, val text: String, val pieces: Int = 1)

/** One stored section (`c` unit): its title, where it starts, and its passages. */
@Serializable
data class StructureSection(val title: String, val where: String, val units: List<StructureUnit>)

/** What `rag index` would store for one document, without storing it. */
@Serializable
data class Structure(
    val root: String,
    val title: String,
    val kind: String,
    val sectionName: String,
    val passageName: String,
    val fragmentName: String,
    val filesRead: Int?,
    val skipped: List<String>,
    val sections: List<StructureSection>,
    val fragments: Int,
    val warnings: List<String>,
)

/**
 * The report behind `rag structure` (plan step 5, part 5a): exactly what `rag index` would store, read
 * through the same `BookReader`, so the code heuristics (and document structure) can be tuned by eye.
 *
 * **What it must do.** Describe a [StructuredBook] as sections and their passages, with where each sits,
 * its size in characters and tokens, and how many pieces a function the size cap split into; plus what a
 * code project's reader skipped, the totals, and the scanner's warnings. Render it as text or JSON.
 *
 * **How ([of]).**
 * 1. Level names come from `DocumentKinds.of(book.kind).levels`: root, section, passage, fragment. The title
 *    is `book.title`, or [fallbackTitle] when it is null.
 * 2. One [StructureSection] per segmented chapter, in order, titled with the chapter title. Its `where`: for
 *    `PageKind.FILE`, the page label of the chapter's first page (the file's path); otherwise
 *    `citation(kind, startLabel, endLabel)` over the chapter's page range, with kind = the page kind's name
 *    in lowercase (`citation` is in `Main.kt`).
 * 3. Passages are the chapter's paragraphs. Consecutive paragraphs inside the same explicit block
 *    (`book.book.blocks`: one function each) become ONE unit with `pieces` = how many there are; any other
 *    paragraph is a unit with `pieces` = 1. A unit's `chars` = end − start + 1 of the whole span; `tokens` =
 *    the sum of `Segmenter().tokenEstimate` over its paragraphs; `text` = the first line of its text, trimmed,
 *    cut to 57 characters + `...` when longer than 60.
 * 4. A unit's `where`: for `PageKind.FILE`, `L<a>` or `L<a>–L<b>` (en dash), the 1-based lines of its first
 *    and last character counted from the start of its file's page; otherwise the page citation as in 2.
 * 5. `filesRead` = the number of project files for a book with project files, else null; `skipped` =
 *    `book.skippedFiles`; `fragments` = the number of phrases over all paragraphs; `warnings` = `book.plan.warnings`.
 *
 * **Text ([text]).** Lines, in order:
 * - `<root> <title> (<kind>)`;
 * - only when `filesRead` is not null: `files: <read> read, <n> skipped`, then, when n > 0, ` (` + one
 *   `<count> <reason>` per reason (the text after the last `: ` of each skipped entry), most frequent first and
 *   by reason on ties, joined by `, ` + `)`;
 * - when [depth] ≥ 1, per section: two spaces, `<title>  [<where>]`; when [depth] ≥ 2, under it per unit: four
 *   spaces, `<where>  <chars> chars  <tokens> tok  <text>`, plus `  → <pieces> pieces` when pieces > 1;
 * - `totals: <sections> <section>s, <units> <passage>s, <fragments> <fragment>s, <warnings> warnings`, where
 *   units counts units (a split function once);
 * - one `warning: <w>` line per warning.
 * Lines end with `\n`, the last one included.
 *
 * **JSON ([json]).** The [Structure] encoded by kotlinx.serialization, pretty-printed.
 */
object StructureReport {

    /**
     * Build a [Structure] from a [StructuredBook].
     */
    fun of(book: StructuredBook, fallbackTitle: String): Structure {
        val kind = DocumentKinds.of(book.kind)
        val title = book.title ?: fallbackTitle

        // Build sections from segmented chapters
        val sections = buildSections(book)

        // Count fragments (total phrases across all paragraphs)
        val fragments = book.segments.sumOf { seg ->
            seg.paragraphs.sumOf { it.phrases.size }
        }

        return Structure(
            root = kind.levels.root,
            title = title,
            kind = kind.name,
            sectionName = kind.levels.section,
            passageName = kind.levels.passage,
            fragmentName = kind.levels.fragment,
            filesRead = if (book.projectFiles.isNotEmpty()) book.projectFiles.size else null,
            skipped = book.skippedFiles,
            sections = sections,
            fragments = fragments,
            warnings = book.plan.warnings,
        )
    }

    private fun buildSections(book: StructuredBook): List<StructureSection> {
        val nl = Newlines(book.book.text)
        return book.segments.map { seg ->
            val c = seg.chapter
            val where = if (book.pageKind == PageKind.FILE) book.book.pageLabel(c.pageStart)
                else citation(book.pageKind.name.lowercase(), book.book.pageLabel(c.pageStart), book.book.pageLabel(c.pageEnd))
            StructureSection(c.title, where, buildUnits(book, seg.paragraphs, nl))
        }
    }

    private fun buildUnits(book: StructuredBook, paragraphs: List<rag.structure.ParagraphSpan>, nl: Newlines): List<StructureUnit> {
        val text = book.book.text
        val blocks = book.book.blocks ?: emptyList()
        val segmenter = Segmenter()
        val units = mutableListOf<StructureUnit>()
        var i = 0
        while (i < paragraphs.size) {
            val p = paragraphs[i]
            // Blocks are sorted and disjoint: the one holding p, if any, by binary search on its start.
            val k = blocks.binarySearch { b -> when { b.last < p.start -> -1; b.first > p.start -> 1; else -> 0 } }
            val block = if (k >= 0 && p.end <= blocks[k].last) blocks[k] else null
            var j = i + 1
            if (block != null) while (j < paragraphs.size && paragraphs[j].start >= block.first && paragraphs[j].end <= block.last) j++
            val first = paragraphs[i]; val last = paragraphs[j - 1]
            units += StructureUnit(
                where = whereOf(book, nl, first.start, last.end),
                chars = last.end - first.start + 1,
                tokens = paragraphs.subList(i, j).sumOf { segmenter.tokenEstimate(text, it.start, it.end) },
                text = truncateFirstLine(text.substring(first.start, first.end + 1)),
                pieces = j - i,
            )
            i = j
        }
        return units
    }

    /** Offsets of every `'\n'` in the book text, for line numbers by binary search. */
    private class Newlines(text: String) {
        private val at = text.indices.filter { text[it] == '\n' }.toIntArray()

        /** Number of line breaks before [offset]. */
        fun before(offset: Int): Int { val i = at.binarySearch(offset); return if (i >= 0) i else -i - 1 }
    }

    /** 1-based line of [offset] within its file's page. */
    private fun lineInFile(book: StructuredBook, nl: Newlines, offset: Int): Int {
        val pageStart = book.book.pageStarts[book.book.pageOf(offset) - 1]
        return nl.before(offset) - nl.before(pageStart) + 1
    }

    /** `L<a>` / `L<a>–L<b>` for file pages, else the page citation of `[start, end]`. */
    private fun whereOf(book: StructuredBook, nl: Newlines, start: Int, end: Int): String =
        if (book.pageKind == PageKind.FILE) {
            val a = lineInFile(book, nl, start); val b = lineInFile(book, nl, end)
            if (a == b) "L$a" else "L$a\u2013L$b"
        } else {
            citation(book.pageKind.name.lowercase(), book.book.pageLabel(book.book.pageOf(start)), book.book.pageLabel(book.book.pageOf(end)))
        }

    /**
     * Truncate text to first line, trimmed, cut to 57 chars + "..." when > 60.
     */
    private fun truncateFirstLine(text: String): String {
        val firstLine = text.split('\n').firstOrNull()?.trim() ?: ""
        if (firstLine.length > 60) {
            return firstLine.substring(0, 57) + "..."
        }
        return firstLine
    }

    /**
     * Render a [Structure] as human-readable text at the given [depth].
     */
    fun text(structure: Structure, depth: Int = 2): String {
        val sb = StringBuilder()

        // Root line
        sb.append(structure.root)
            .append(' ')
            .append(structure.title)
            .append(" (")
            .append(structure.kind)
            .append(")\n")

        // Files line
        if (structure.filesRead != null) {
            val n = structure.skipped.size
            sb.append("files: ").append(structure.filesRead).append(" read, ").append(n).append(" skipped")
            if (n > 0) {
                // Count reasons: text after the last ": " of each skipped entry
                val reasonCounts = mutableMapOf<String, Int>()
                for (entry in structure.skipped) {
                    val lastColon = entry.lastIndexOf(": ")
                    val reason = if (lastColon >= 0) entry.substring(lastColon + 2) else entry
                    reasonCounts[reason] = reasonCounts.getOrDefault(reason, 0) + 1
                }
                // Sort by count desc, then by reason asc on ties
                val sorted = reasonCounts.toList().sortedWith(
                    compareByDescending<Pair<String, Int>> { it.second }
                        .thenBy { it.first }
                )
                sb.append(" (")
                    .append(sorted.joinToString(", ") { "${it.second} ${it.first}" })
                    .append(")")
            }
            sb.append('\n')
        }

        // Sections
        if (depth >= 1) {
            for (section in structure.sections) {
                sb.append("  ")
                    .append(section.title)
                    .append("  [")
                    .append(section.where)
                    .append("]\n")

                // Units (depth >= 2)
                if (depth >= 2) {
                    for (unit in section.units) {
                        sb.append("    ")
                            .append(unit.where)
                            .append("  ")
                            .append(unit.chars)
                            .append(" chars  ")
                            .append(unit.tokens)
                            .append(" tok  ")
                            .append(unit.text)
                        if (unit.pieces > 1) {
                            sb.append("  → ")
                                .append(unit.pieces)
                                .append(" pieces")
                        }
                        sb.append('\n')
                    }
                }
            }
        }

        // Totals line
        val sectionCount = structure.sections.size
        val unitCount = structure.sections.sumOf { it.units.size }
        val warningCount = structure.warnings.size
        sb.append("totals: ")
            .append(sectionCount)
            .append(' ')
            .append(structure.sectionName)
            .append("s, ")
            .append(unitCount)
            .append(' ')
            .append(structure.passageName)
            .append("s, ")
            .append(structure.fragments)
            .append(' ')
            .append(structure.fragmentName)
            .append("s, ")
            .append(warningCount)
            .append(" warnings\n")

        // Warnings
        for (w in structure.warnings) {
            sb.append("warning: ").append(w).append('\n')
        }

        return sb.toString()
    }

    /**
     * Encode a [Structure] as pretty-printed JSON.
     */
    fun json(structure: Structure): String =
        Json { prettyPrint = true }.encodeToString(structure)
}
