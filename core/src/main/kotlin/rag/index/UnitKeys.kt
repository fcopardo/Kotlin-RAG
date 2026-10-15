package rag.index

import rag.StructuredBook
import rag.extract.Normalizer

enum class UnitKind(val code: Char) {
    PHRASE('p'), PARAGRAPH('g'), CHAPTER('c'), BOOK('b');

    companion object {
        fun of(code: Char): UnitKind = entries.first { it.code == code }
    }
}

/**
 * One unit ready to store. [parentIndex] is the position of the parent in the same list, so
 * records can be inserted in order and parent ids resolved as they go. [key] isn't stored: it's
 * rebuilt from hash, occurrence, kind and chapter (or book id) when needed.
 */
data class UnitRecord(
    val kind: UnitKind,
    val key: String,
    val hash: String,
    val occurrence: Int,
    val chapter: Int?,
    val parentIndex: Int?,
    val ordinal: Int,
    val start: Int,
    val end: Int,
    val pageStart: Int,
    val pageEnd: Int,
    val title: String? = null,
    val isCode: Boolean = false,
)

/** A unit key split into its parts. [scope] is the chapter number (`c`) or book id (`b`); null for a book key. */
data class ParsedKey(val hash: String, val occurrence: Int, val kind: UnitKind, val scope: Int?)

/**
 * Builds every unit of a book with its key (SPEC §6.1): `<hash>-<n><type>-<m><scope>`.
 *
 * `n` counts earlier occurrences of the same hash and type in the same scope, from 0. Phrases and
 * paragraphs are scoped by chapter number (`c`), chapters by book id (`b`). The book key is
 * `<hash>-0b`. Order: book, then each chapter followed by its paragraphs, each followed by its phrases.
 */
object UnitKeys {

    fun build(structured: StructuredBook, bookId: Long): List<UnitRecord> {
        val book = structured.book
        val text = book.text
        val records = mutableListOf<UnitRecord>()
        if (text.isEmpty()) return records

        fun hash(start: Int, end: Int) = Normalizer.hash(text.substring(start, end + 1))
        val lastPage = book.pageOf(text.length - 1)

        val bookHash = hash(0, text.length - 1)
        records += UnitRecord(UnitKind.BOOK, format(bookHash, 0, UnitKind.BOOK, null), bookHash, 0, null,
            null, 0, 0, text.length - 1, book.pageOf(0), lastPage, structured.title)

        val chapterCounts = HashMap<String, Int>()
        structured.segments.forEach { segmented ->
            val chapter = segmented.chapter
            val chapterHash = hash(chapter.charStart, chapter.charEnd)
            val chapterIndex = records.size
            // A chapter's ordinal is its number (0 = front matter), so chapters keep their numbering.
            val chapterOccurrence = next(chapterCounts, chapterHash)
            records += UnitRecord(
                UnitKind.CHAPTER, format(chapterHash, chapterOccurrence, UnitKind.CHAPTER, bookId.toInt()), chapterHash,
                chapterOccurrence, chapter.number,
                0, chapter.number, chapter.charStart, chapter.charEnd, chapter.pageStart, chapter.pageEnd, chapter.title,
            )

            // Counts per chapter, separately for each unit type.
            val paragraphCounts = HashMap<String, Int>()
            val phraseCounts = HashMap<String, Int>()
            val number = chapter.number
            segmented.paragraphs.forEachIndexed { paragraphOrdinal, p ->
                val paragraphHash = hash(p.start, p.end)
                val paragraphIndex = records.size
                val paragraphOccurrence = next(paragraphCounts, paragraphHash)
                records += UnitRecord(
                    UnitKind.PARAGRAPH, format(paragraphHash, paragraphOccurrence, UnitKind.PARAGRAPH, number), paragraphHash,
                    paragraphOccurrence, number,
                    chapterIndex, paragraphOrdinal, p.start, p.end, book.pageOf(p.start), book.pageOf(p.end), isCode = p.isCode,
                )
                p.phrases.forEachIndexed { phraseOrdinal, ph ->
                    val phraseHash = hash(ph.start, ph.end)
                    val phraseOccurrence = next(phraseCounts, phraseHash)
                    records += UnitRecord(
                        UnitKind.PHRASE, format(phraseHash, phraseOccurrence, UnitKind.PHRASE, number), phraseHash,
                        phraseOccurrence, number,
                        paragraphIndex, phraseOrdinal, ph.start, ph.end, book.pageOf(ph.start), book.pageOf(ph.end),
                    )
                }
            }
        }
        return records
    }

    /**
     * The key for a unit. [scope] is the chapter number for phrases and paragraphs, the book id
     * for chapters, and ignored for the book.
     */
    fun format(hash: String, occurrence: Int, kind: UnitKind, scope: Int?): String = when (kind) {
        UnitKind.BOOK -> "$hash-${occurrence}b"
        UnitKind.CHAPTER -> "$hash-${occurrence}c-${scope}b"
        UnitKind.PARAGRAPH, UnitKind.PHRASE -> "$hash-$occurrence${kind.code}-${scope}c"
    }

    private val keyPattern = Regex("^([0-9a-f]{16})-(\\d+)([pgcb])(?:-(\\d+)([cb]))?$")

    /** Splits a key, or returns null if it isn't one. */
    fun parse(key: String): ParsedKey? {
        val m = keyPattern.matchEntire(key.trim()) ?: return null
        val (hash, occurrence, kindCode, scope, scopeCode) = m.destructured
        val kind = UnitKind.of(kindCode.single())
        val expectedScope = when (kind) {
            UnitKind.BOOK -> ""
            UnitKind.CHAPTER -> "b"
            UnitKind.PARAGRAPH, UnitKind.PHRASE -> "c"
        }
        if (scopeCode != expectedScope) return null
        return ParsedKey(hash, occurrence.toInt(), kind, scope.toIntOrNull())
    }

    private fun next(counts: MutableMap<String, Int>, hash: String): Int {
        val n = counts[hash] ?: 0
        counts[hash] = n + 1
        return n
    }
}
