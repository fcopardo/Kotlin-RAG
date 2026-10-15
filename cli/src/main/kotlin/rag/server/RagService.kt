package rag.server

import kotlinx.serialization.Serializable
import rag.citation
import rag.embed.Embedder
import rag.index.UnitKind
import rag.search.Hit
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import rag.summarize.Subjects

/** A book as agents see it: what it is, what it's about, how it can be searched. */
@Serializable
data class BookInfo(
    val id: Long,
    val title: String?,
    val format: String,
    val chapters: Int,
    val models: List<String>,
    val subjects: List<SubjectInfo>,
)

@Serializable
data class SubjectInfo(val subject: String, val weight: String)

@Serializable
data class ChapterInfo(val number: Int, val title: String, val pages: String)

/** One page of a chapter's text; [nextOffset] is null on the last one. */
@Serializable
data class ChapterText(
    val bookId: Long,
    val chapter: Int,
    val title: String,
    val pages: String,
    val offset: Int,
    val totalChars: Int,
    val nextOffset: Int?,
    val text: String,
)

/** A search hit in few tokens, for language models: where it is, and the text to quote. */
@Serializable
data class Passage(
    val book: String?,
    val bookId: Long,
    val chapter: String,
    val pages: String,
    val match: String,
    val text: String,
    /** The sentence in [text] closest to the question. */
    val bestSentence: String,
    /** For summary hits: what the summary says (the passage is the best paragraph it covers). */
    val summary: String? = null,
)

/**
 * What the MCP server and the HTTP API offer, over one warm database connection (SPEC §17). Search
 * keeps its caches between calls; a write by another process (`rag index`, `rag embed`) is noticed
 * through SQLite's `data_version` and the caches are rebuilt. Calls run one at a time.
 *
 * @param embedderFor the embedder for a model name, or null for keyword search only.
 * @param embedModel the query model; by default the first model of the books searched.
 */
class RagService(
    private val store: SqliteStore,
    private val embedderFor: (String) -> Embedder?,
    private val embedModel: String? = null,
    private val warn: (String) -> Unit = { System.err.println("Warning: $it") },
) {
    private val searchers = HashMap<String?, Searcher>()
    private val embedders = HashMap<String, Embedder?>()
    private var dataVersion = -1L

    @Synchronized
    fun search(query: String, k: Int = 5, bookId: Long? = null, subject: String? = null, mode: Mode = Mode.HYBRID): List<Hit> {
        require(query.isNotBlank()) { "query is empty" }
        require(k in 1..50) { "k must be between 1 and 50" }
        if (bookId != null) requireBook(bookId)
        refreshIfChanged()
        val books = if (bookId != null) listOfNotNull(store.book(bookId)) else store.books()
        // The most common primary model in scope; books without it are searched by keywords.
        val model = if (mode == Mode.LEXICAL) null
        else embedModel ?: books.mapNotNull { it.embedModel }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return searcher(model).search(query, k, bookId, mode, subject)
    }

    @Synchronized
    fun passages(query: String, k: Int = 5, bookId: Long? = null, subject: String? = null, mode: Mode = Mode.HYBRID): List<Passage> =
        search(query, k, bookId, subject, mode).map { h ->
            Passage(
                book = h.bookTitle, bookId = h.bookId, chapter = h.chapter.title,
                pages = citation(h.pageKind, h.paragraph.pageStartLabel, h.paragraph.pageEndLabel),
                match = h.matchType.name.lowercase(), text = h.paragraph.text, bestSentence = h.phrase.text,
                summary = h.summary,
            )
        }

    @Synchronized
    fun books(): List<BookInfo> {
        val subjects = store.allSubjects()
        return store.books().map { b ->
            BookInfo(
                b.id, b.title, b.format, store.units(b.id, UnitKind.CHAPTER).size, b.models.map { it.model },
                subjects[b.id].orEmpty().map { SubjectInfo(it.subject, weightName(it.weight)) },
            )
        }
    }

    @Synchronized
    fun chapters(bookId: Long): List<ChapterInfo> {
        val book = requireBook(bookId)
        val labels = store.pageLabels(bookId)
        return store.units(bookId, UnitKind.CHAPTER).map { c ->
            ChapterInfo(c.ordinal, c.title.orEmpty(), pages(book.pageKind, labels, c.pageStart, c.pageEnd))
        }
    }

    /** Up to [maxChars] of chapter [number]'s text from [offset]; long chapters come in pages. */
    @Synchronized
    fun readChapter(bookId: Long, number: Int, offset: Int = 0, maxChars: Int = 12_000): ChapterText {
        val book = requireBook(bookId)
        val chapter = store.units(bookId, UnitKind.CHAPTER).firstOrNull { it.ordinal == number }
            ?: throw NoSuchElementException("book $bookId has no chapter $number (see its chapter list)")
        val text = store.bookText(bookId).substring(chapter.start, chapter.end + 1)
        require(offset in 0..text.length) { "offset must be between 0 and ${text.length}" }
        val end = minOf(text.length, offset + maxChars).let { limit ->
            // End the page at a paragraph or line break when there's one in the last fifth.
            if (limit == text.length) limit
            else text.lastIndexOf('\n', limit).takeIf { it > offset + maxChars * 4 / 5 }?.plus(1) ?: limit
        }
        return ChapterText(
            bookId, number, chapter.title.orEmpty(), pages(book.pageKind, store.pageLabels(bookId), chapter.pageStart, chapter.pageEnd),
            offset, text.length, end.takeIf { it < text.length }, text.substring(offset, end),
        )
    }

    private fun requireBook(bookId: Long) = store.book(bookId) ?: throw NoSuchElementException("no book with id $bookId (see the book list)")

    private fun searcher(model: String?): Searcher = searchers.getOrPut(model) {
        val embedder = model?.let { m -> embedders.getOrPut(m) { embedderFor(m) } }
        Searcher(store, embedder, warn = warn, keywordsOnModelMismatch = true)
    }

    /** Another connection committed (a book indexed, embedded or deleted): drop cached vectors and texts. */
    private fun refreshIfChanged() {
        val version = store.db.query("PRAGMA data_version", emptyList()) { it.long(1) }.single()
        if (version != dataVersion) {
            searchers.clear()
            dataVersion = version
        }
    }

    private fun pages(kind: String, labels: List<String>, start: Int, end: Int) =
        citation(kind, labels.getOrElse(start - 1) { "$start" }, labels.getOrElse(end - 1) { "$end" })

    private fun weightName(weight: Double) = when {
        weight >= Subjects.MAIN -> "main"
        weight >= Subjects.TANGENTIAL -> "tangential"
        else -> "broad"
    }
}
