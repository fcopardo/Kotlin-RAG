package rag.android

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rag.BookReader
import rag.PdfBookSource
import rag.ReadOptions
import rag.embed.Embedder
import rag.code.CodeBookSource
import rag.document.DocxBookSource
import rag.document.HtmlBookSource
import rag.document.TextBookSource
import rag.epub.EpubBookSource
import rag.index.EmbedResult
import rag.index.IndexResult
import rag.index.Indexer
import rag.search.Hit
import rag.search.Mode
import rag.search.Searcher
import rag.store.BookRow
import rag.store.BundledSqlDriver
import rag.store.SqliteStore
import rag.summarize.BookSubject
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Index and search books on Android (SPEC §15.2). Everything runs on [Dispatchers.IO], one call at
 * a time (one SQLite connection). Indexes are interchangeable with the desktop's.
 *
 * Embedding can be cancelled between batches: the book stays `partial` and [embed] resumes it.
 * Reading and splitting a book (before embedding) runs to completion once started.
 *
 * Models come from a [ModelProvider] following a [ModelPolicy] (SPEC §19.2): the engine hands the policy to the
 * provider and asks the provider for the embedder each operation needs. Neither is closed by [close]: they belong
 * to the caller and may be shared.
 *
 * @param policy the model rules, usually [ModelPolicies.current].
 * @param provider loads the models, e.g. [RagModels.provider]; its embedder may be null (keyword-only indexes and search).
 */
class RagEngine(
    context: Context,
    dbFile: File,
    private val policy: ModelPolicy,
    private val provider: ModelProvider,
    private val embedDim: Int = Indexer.DEFAULT_DIM,
    /**
     * RAM for cached 1-bit vectors. The default 16 MB holds ~190k paragraphs (~115 books); on the
     * S25, 100 books searched in 38 ms with it and in 414 ms with 4 MB, so small phones use it too.
     */
    private val bitsCacheBytes: Long = Searcher.BITS_CACHE_BYTES,
    /** The language queries are written in unless a search says (SPEC §19.4), e.g. the app's language; null: detect. */
    private val defaultLanguage: String? = null,
) : Closeable {

    /**
     * One caller-chosen embedder, or none (keyword-only indexes and search): a [FixedModelProvider] under the
     * current policy. The [embedder] is not closed by [close].
     */
    constructor(
        context: Context,
        dbFile: File,
        embedder: Embedder? = null,
        embedDim: Int = Indexer.DEFAULT_DIM,
        bitsCacheBytes: Long = Searcher.BITS_CACHE_BYTES,
    ) : this(context, dbFile, ModelPolicies.current, FixedModelProvider(embedder), embedDim, bitsCacheBytes)

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        provider.bind(policy)
    }

    private val store = SqliteStore(dbFile.path, BundledSqlDriver())
    // Explicit sources and driver: no reliance on ServiceLoader surviving the app's R8 setup.
    private val indexer = Indexer(store, BookReader(listOf(
        PdfBookSource(), EpubBookSource(), TextBookSource(), HtmlBookSource(), DocxBookSource(), CodeBookSource(),
    )))
    private val lock = Mutex()
    /**
     * One searcher per embedding model (null = keywords only), kept while the provider hands out the same
     * embedder; a model that was unloaded and opened again gets a new searcher, so no searcher keeps a closed one.
     */
    private val searchers = HashMap<String?, Pair<Embedder?, Searcher>>()

    private fun searcher(): Searcher {
        val embedder = provider.embedder()
        searchers[embedder?.model]?.let { (e, s) -> if (e === embedder) return s }
        return Searcher(store, embedder, warn = { android.util.Log.w(TAG, it) }, keywordsOnModelMismatch = true, bitsCacheBytes = bitsCacheBytes, defaultLanguage = defaultLanguage)
            .also { searchers[embedder?.model] = embedder to it }
    }

    /** Reports embedding progress: texts embedded so far and in total. */
    fun interface Progress {
        fun onBatch(done: Int, total: Int)
    }

    suspend fun index(
        file: File,
        tocPages: Set<Int>? = null,
        reindex: Boolean = false,
        locationSize: Int = ReadOptions.DEFAULT_LOCATION_SIZE,
        /** False stores the structure only (keyword search works); [embed] adds vectors later. */
        embed: Boolean = true,
        /** The document's language as an ISO 639 code, instead of detecting it (SPEC §19.4). */
        language: String? = null,
        progress: Progress = Progress { _, _ -> },
    ): IndexResult = locked {
        val context = coroutineContext
        try {
            indexer.index(file, tocPages, reindex, provider.embedder().takeIf { embed }, embedDim, locationSize, language) { done, total ->
                context.ensureActive()
                progress.onBatch(done, total)
            }
        } finally {
            searchers.clear()
        }
    }

    /** Embeds what [index] didn't finish (e.g. after a cancellation). */
    suspend fun embed(bookId: Long, progress: Progress = Progress { _, _ -> }): EmbedResult = locked {
        val embedder = requireNotNull(provider.embedder()) { "RagEngine has no embedder" }
        val context = coroutineContext
        try {
            indexer.embed(bookId, embedder, embedDim) { done, total ->
                context.ensureActive()
                progress.onBatch(done, total)
            }
        } finally {
            searchers.clear()
        }
    }

    /**
     * [subject] limits the search to books about it (SPEC §8.5); [kind] to documents of that kind: "book",
     * "paper", "contract" or "code" (SPEC §18.3). Null searches everything.
     */
    suspend fun search(
        query: String,
        k: Int = 5,
        bookId: Long? = null,
        mode: Mode = Mode.HYBRID,
        subject: String? = null,
        kind: String? = null,
        /** Only documents in these languages (ISO 639 codes); code always matches. Null: all (SPEC §19.4). */
        languages: Collection<String>? = null,
        /** The language [query] is written in; null: the engine's default language, else detected (SPEC §19.4). */
        queryLanguage: String? = null,
    ): List<Hit> = locked {
        searcher().search(query, k, bookId, mode, subject, kind, languages, queryLanguage)
    }

    /**
     * The best [kPerKind] hits of each kind of document, side by side ([Searcher.searchByKind]): a search for
     * "coroutines" shows the book's passages and the code's functions in their own sections.
     */
    suspend fun searchByKind(
        query: String,
        kPerKind: Int = 3,
        kinds: List<String>? = null,
        mode: Mode = Mode.HYBRID,
        subject: String? = null,
        languages: Collection<String>? = null,
        queryLanguage: String? = null,
    ): Map<String, List<Hit>> = locked {
        searcher().searchByKind(query, kPerKind, kinds, mode, subject, languages, queryLanguage)
    }

    /** [sectionText] of [hit]'s section and paragraph. */
    suspend fun sectionText(hit: Hit, maxChars: Int = SECTION_MAX_CHARS): SectionText? =
        sectionText(hit.bookId, hit.chapter.unitKey, hit.paragraph.unitKey, maxChars)

    /**
     * The stored text of section [sectionKey] of book [bookId], with paragraph [paragraphKey] marked, for
     * showing a hit in place without the original file. Null if the book, the section or the paragraph isn't
     * in this index, or the paragraph isn't inside the section.
     *
     * 1. Look up both units with [SqliteStore.findByKey] for [bookId]; take the first match of each.
     * 2. Unit offsets are inclusive (SPEC §6): the section's text is `bookText.substring(section.start, section.end + 1)`.
     *    The paragraph must lie inside it (`section.start <= paragraph.start` and `paragraph.end <= section.end`), else null.
     * 3. A section of at most [maxChars] characters is returned whole. A longer one is cut to a window of
     *    exactly [maxChars] characters inside the section, centered on the paragraph: start at
     *    `paragraph.start - (maxChars - paragraphLength) / 2`, clamped so the window stays inside the section
     *    (first to `section.end + 1 - maxChars` at most, then to `section.start` at least). A paragraph longer
     *    than [maxChars] starts the window at its own start.
     * 4. [SectionText.highlightStart] is `paragraph.start` and [SectionText.highlightEnd] `paragraph.end + 1`, minus the
     *    window start, clamped to `[0, text.length]`: `text.substring(highlightStart, highlightEnd)` is [Hit.paragraph]'s
     *    text. [SectionText.title] is the section unit's title, or "" when it has none.
     */
    suspend fun sectionText(bookId: Long, sectionKey: String, paragraphKey: String, maxChars: Int = SECTION_MAX_CHARS): SectionText? =
        locked {
            val sectionList = store.findByKey(sectionKey, bookId)
            if (sectionList.isEmpty()) return@locked null
            val section = sectionList.first()

            val paragraphList = store.findByKey(paragraphKey, bookId)
            if (paragraphList.isEmpty()) return@locked null
            val paragraph = paragraphList.first()

            // Paragraph must lie inside the section [start, end)
            if (paragraph.start < section.start || paragraph.end > section.end) return@locked null

            // Unit offsets are inclusive: a unit is bookText[start..end].
            val sectionEnd = section.end + 1
            val paragraphEnd = paragraph.end + 1
            val sectionLen = sectionEnd - section.start
            val paragraphLen = paragraphEnd - paragraph.start
            val windowStart = when {
                sectionLen <= maxChars -> section.start
                paragraphLen >= maxChars -> paragraph.start
                else -> (paragraph.start - (maxChars - paragraphLen) / 2).coerceAtMost(sectionEnd - maxChars).coerceAtLeast(section.start)
            }
            val windowEnd = minOf(sectionEnd, windowStart + maxChars)
            val text = store.bookText(bookId).substring(windowStart, windowEnd)

            SectionText(
                title = section.title.orEmpty(),
                text = text,
                highlightStart = (paragraph.start - windowStart).coerceIn(0, text.length),
                highlightEnd = (paragraphEnd - windowStart).coerceIn(0, text.length),
                cutBefore = windowStart > section.start,
                cutAfter = windowEnd < sectionEnd,
            )
        }

    suspend fun books(): List<BookRow> = locked { store.books() }

    /** What a book is about: its file's subjects, plus any from `rag subjects` on the desktop (SPEC §8.5). */
    suspend fun subjects(bookId: Long): List<BookSubject> = locked { store.subjects(bookId) }

    suspend fun deleteBook(bookId: Long) = locked { store.deleteBook(bookId).also { searchers.clear() } }

    /** Closes the database. The provider and its models belong to the caller, who closes them (they may be shared). */
    override fun close() = store.close()

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    companion object {
        /** Characters of a section [sectionText] returns at most: enough for any section of a book. */
        const val SECTION_MAX_CHARS = 100_000
        private const val TAG = "RagEngine"
    }
}
