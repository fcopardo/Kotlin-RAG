package rag.search

import kotlinx.serialization.Serializable
import rag.RagException
import rag.embed.Embedder
import rag.embed.Quantizer
import rag.embed.Vectors
import rag.extract.Normalizer
import rag.index.UnitKind
import rag.store.BookRow
import rag.store.SqliteStore
import rag.store.StoredUnit
import java.util.PriorityQueue

enum class Mode { HYBRID, LEXICAL, VECTOR }

enum class MatchType { EXACT_BOOK, EXACT_CHAPTER, EXACT_PARAGRAPH, EXACT_PHRASE, LEXICAL, VECTOR, HYBRID, SUMMARY }

/** Pages are ordinals into the book's pages; labels are what to cite ("13", "xii", "loc 4"). */
@Serializable
data class UnitRef(
    val unitKey: String,
    val text: String,
    val pageStart: Int,
    val pageEnd: Int,
    val pageStartLabel: String,
    val pageEndLabel: String,
)

@Serializable
data class ChapterRef(
    val number: Int,
    val title: String,
    val pageStart: Int,
    val pageEnd: Int,
    val unitKey: String,
    val pageStartLabel: String,
    val pageEndLabel: String,
)

/** One search result (SPEC §8.2). The JSON form is the contract for LLM tools. */
@Serializable
data class Hit(
    val score: Double,
    val matchType: MatchType,
    val phrase: UnitRef,
    val paragraph: UnitRef,
    val chapter: ChapterRef,
    val bookId: Long,
    val bookTitle: String?,
    /** "pdf", "print" or "location": how to read the page labels (SPEC §16.5). */
    val pageKind: String,
    val summary: String? = null,
)

/**
 * Search (SPEC §8.3): exact hash lookup, BM25 over paragraphs, binary-then-int8 vector search,
 * reciprocal rank fusion, then the closest phrase inside each paragraph.
 *
 * Memory: only the binary vectors of searched books (64 bytes per paragraph at 512 dims) and the
 * text of at most [textCacheBooks] books stay in RAM.
 */
class Searcher(
    private val store: SqliteStore,
    private val embedder: Embedder?,
    private val textCacheBooks: Int = 2,
    private val warn: (String) -> Unit = { System.err.println("Warning: $it") },
    /** Weight of the keyword list in the fusion (SPEC §8.3); the vector list has 1. */
    private val lexicalWeight: Double = LEXICAL_WEIGHT,
    /** Reciprocal rank fusion constant: smaller makes rank matter more (SPEC §8.3). */
    private val rrfK: Int = RRF_K,
) {
    private val texts = object : LinkedHashMap<Long, String>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>) = size > textCacheBooks
    }
    private val binaryIndexes = HashMap<Long, BinaryIndex>()
    private val pageLabels = HashMap<Long, List<String>>()

    private class BinaryIndex(val unitIds: LongArray, val bits: Array<LongArray>)

    private class QueryContext(val text: String, val terms: String?, val vector: FloatArray?)

    fun search(query: String, k: Int = 5, bookId: Long? = null, mode: Mode = Mode.HYBRID): List<Hit> {
        require(k > 0) { "k must be positive" }
        val books = if (bookId != null) listOfNotNull(store.book(bookId)) else store.books()
        if (books.isEmpty()) return emptyList()
        val n = maxOf(4 * k, 20)

        val vector = if (mode == Mode.LEXICAL) null else queryVector(query, books, required = mode == Mode.VECTOR)
        val context = QueryContext(query, FtsQuery.anyTerm(query), vector)

        // A one-sentence paragraph and its phrase share a hash: keep one hit per paragraph.
        val exact = exactHits(query, books, context).distinctBy { it.second }

        val lexical = if (mode == Mode.VECTOR || context.terms == null) emptyList()
        else books.flatMap { b -> store.ftsSearch("para_fts", context.terms, b.id, limit = n) }
        val dense = vector?.let { v -> vectorSearch(v, books, n) }.orEmpty()

        val lexicalSet = lexical.toSet()
        val denseSet = dense.toSet()
        val fused = Fusion.rrf(listOf(lexical, dense), k = rrfK, weights = listOf(lexicalWeight, 1.0))
        val ranked = fused.map { (id, score) ->
            val type = when {
                id in lexicalSet && id in denseSet -> MatchType.HYBRID
                id in lexicalSet -> MatchType.LEXICAL
                else -> MatchType.VECTOR
            }
            Triple(id, score, type)
        }

        val seen = exact.map { it.second }.toMutableSet()
        val hits = exact.map { it.first }.toMutableList()
        for ((id, score, type) in ranked) {
            if (hits.size >= k) break
            if (!seen.add(id)) continue
            val paragraph = store.unit(id) ?: continue
            hits += hit(paragraph, null, type, score, context)
        }
        return hits.take(k)
    }

    // --- exact path ---

    /** Hits for units whose text equals the query, book → chapter → paragraph → phrase. Pairs are (hit, paragraph id). */
    private fun exactHits(query: String, books: List<BookRow>, context: QueryContext): List<Pair<Hit, Long>> {
        if (Normalizer.hashNormalize(query).isEmpty()) return emptyList()
        val bookIds = books.map { it.id }.toSet()
        val order = listOf(UnitKind.BOOK, UnitKind.CHAPTER, UnitKind.PARAGRAPH, UnitKind.PHRASE)
        val matches = store.unitsByHash(Normalizer.hash(query))
            .filter { it.bookId in bookIds }
            .sortedBy { order.indexOf(it.kind) }
        return matches.mapNotNull { u ->
            when (u.kind) {
                UnitKind.PHRASE -> store.unit(u.parentId!!)?.let { hit(it, u, MatchType.EXACT_PHRASE, 1.0, context) to it.id }
                UnitKind.PARAGRAPH -> hit(u, null, MatchType.EXACT_PARAGRAPH, 1.0, context) to u.id
                UnitKind.CHAPTER -> firstParagraph(u)?.let { hit(it, null, MatchType.EXACT_CHAPTER, 1.0, context) to it.id }
                UnitKind.BOOK -> store.children(u.id).firstNotNullOfOrNull(::firstParagraph)
                    ?.let { hit(it, null, MatchType.EXACT_BOOK, 1.0, context) to it.id }
            }
        }
    }

    private fun firstParagraph(chapter: StoredUnit): StoredUnit? = store.children(chapter.id).firstOrNull()

    // --- vector path ---

    /** The query's full-length vector, or null if the backend is down and vectors aren't required. */
    private fun queryVector(query: String, books: List<BookRow>, required: Boolean): FloatArray? {
        val embedded = books.filter { it.embedModel != null }
        if (embedded.isEmpty()) {
            if (required) throw RagException.EmbeddingBackendUnavailable("No embeddings in scope. Run `rag embed` first.")
            return null
        }
        if (embedder == null) {
            if (required) throw RagException.EmbeddingBackendUnavailable("Vector search needs an embedding backend")
            return null
        }
        embedded.firstOrNull { it.embedModel != embedder.model }?.let {
            throw RagException.ModelMismatch(
                "Book ${it.id} was embedded with '${it.embedModel}', not '${embedder.model}'. " +
                    "Search with --embed-model ${it.embedModel}, or run `rag embed --book ${it.id}` to re-embed it."
            )
        }
        return try {
            Vectors.l2Normalize(embedder.embedQuery(query))
        } catch (e: RagException.EmbeddingBackendUnavailable) {
            if (required) throw e
            warn("${e.message}; using keyword search only")
            null
        }
    }

    /** Binary Hamming first pass, then int8 rescoring of the closest candidates. Unit ids, best first. */
    private fun vectorSearch(full: FloatArray, books: List<BookRow>, n: Int): List<Long> {
        // Measured on the eval set: 200 candidates make 1-bit + int8 match float search; 100 lost a hit.
        val rescoreCount = maxOf(10 * n / 4, 200)
        val scored = books.filter { it.embedModel != null && it.embedDim != null }.flatMap { book ->
            val dim = book.embedDim!!
            if (dim > full.size) throw RagException.ModelMismatch("Book ${book.id} has $dim-dim vectors; the query has ${full.size}")
            val query = Quantizer.truncate(full, dim)
            val queryBits = Quantizer.bits(query)
            val index = binaryIndex(book.id)

            // Keep the rescoreCount smallest Hamming distances with a bounded max-heap.
            val heap = PriorityQueue<Pair<Int, Long>>(compareByDescending { it.first })
            for (i in index.unitIds.indices) {
                heap += Quantizer.hamming(queryBits, index.bits[i]) to index.unitIds[i]
                if (heap.size > rescoreCount) heap.poll()
            }
            store.vectors(heap.map { it.second }).map { v -> v.unitId to Quantizer.dot(query, v.int8, v.scale) }
        }
        return scored.sortedByDescending { it.second }.take(n).map { it.first }
    }

    private fun binaryIndex(bookId: Long): BinaryIndex = binaryIndexes.getOrPut(bookId) {
        val rows = store.binaryVectors(bookId)
        BinaryIndex(LongArray(rows.size) { rows[it].first }, Array(rows.size) { rows[it].second })
    }

    // --- building hits ---

    private fun hit(paragraph: StoredUnit, phrase: StoredUnit?, type: MatchType, score: Double, context: QueryContext): Hit {
        val chapter = store.unit(paragraph.parentId!!)!!
        val book = store.book(paragraph.bookId)!!
        val closest = phrase ?: closestPhrase(paragraph, context)
        return Hit(score, type, ref(closest), ref(paragraph), chapterRef(chapter), book.id, book.title, book.pageKind)
    }

    /** Best BM25 phrase in the paragraph; else the phrase most similar to the query; else the first. */
    private fun closestPhrase(paragraph: StoredUnit, context: QueryContext): StoredUnit {
        val phrases = store.children(paragraph.id)
        if (phrases.size <= 1) return phrases.firstOrNull() ?: paragraph
        context.terms?.let { terms ->
            store.ftsSearch("phrase_fts", terms, parentId = paragraph.id, limit = 1).firstOrNull()?.let { id ->
                return phrases.first { it.id == id }
            }
        }
        val vector = context.vector
        if (vector != null && embedder != null) {
            val text = text(paragraph.bookId)
            try {
                val vectors = embedder.embedDocuments(phrases.map { text.substring(it.start, it.end + 1) })
                return phrases[vectors.indices.maxBy { Vectors.dot(vector, Vectors.l2Normalize(vectors[it])) }]
            } catch (e: RagException.EmbeddingBackendUnavailable) {
                warn("${e.message}; using the paragraph's first phrase")
            }
        }
        return phrases.first()
    }

    private fun ref(u: StoredUnit) = UnitRef(
        u.key, text(u.bookId).substring(u.start, u.end + 1), u.pageStart, u.pageEnd, label(u.bookId, u.pageStart), label(u.bookId, u.pageEnd),
    )

    private fun chapterRef(c: StoredUnit) =
        ChapterRef(c.ordinal, c.title ?: "", c.pageStart, c.pageEnd, c.key, label(c.bookId, c.pageStart), label(c.bookId, c.pageEnd))

    private fun label(bookId: Long, page: Int): String =
        pageLabels.getOrPut(bookId) { store.pageLabels(bookId) }.getOrNull(page - 1) ?: page.toString()

    private fun text(bookId: Long): String = texts.getOrPut(bookId) { store.bookText(bookId) }

    companion object {
        /**
         * Weight of the keyword list in the fusion; the vector list has 1. On questions that share no
         * words with the answer, keyword hits are noise and at full weight they push out good vector
         * hits (mxbai-embed-large hybrid recall@5 0.65 at 1.0). The eval set showed a flat optimum from
         * 0.2 to 0.7 for both models tested (0.80 mxbai, 0.75 nomic); 0.5 is its middle (SPEC §8.3).
         */
        const val LEXICAL_WEIGHT = 0.5

        /**
         * The usual 60 flattens ranks so much that a paragraph found by both lists, even 20th in each,
         * outscores the vector list's first hit. Measured on three evaluation sets (SPEC §11, §16.8):
         * k = 5 was better or equal everywhere (Compose 0.80/MRR 0.69, Art of War SE 0.75, Gutenberg 0.65).
         */
        const val RRF_K = 5
    }
}
