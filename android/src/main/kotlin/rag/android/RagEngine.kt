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
 * @param embedder for vector search; null gives keyword-only indexes and search. Not closed by [close].
 */
class RagEngine(
    context: Context,
    dbFile: File,
    private val embedder: Embedder? = null,
    private val embedDim: Int = Indexer.DEFAULT_DIM,
    /**
     * RAM for cached 1-bit vectors. The default 16 MB holds ~190k paragraphs (~115 books); on the
     * S25, 100 books searched in 38 ms with it and in 414 ms with 4 MB, so small phones use it too.
     */
    private val bitsCacheBytes: Long = Searcher.BITS_CACHE_BYTES,
) : Closeable {

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    private val store = SqliteStore(dbFile.path, BundledSqlDriver())
    // Explicit sources and driver: no reliance on ServiceLoader surviving the app's R8 setup.
    private val indexer = Indexer(store, BookReader(listOf(PdfBookSource(), EpubBookSource())))
    private val lock = Mutex()
    private var searcher: Searcher? = null

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
        progress: Progress = Progress { _, _ -> },
    ): IndexResult = locked {
        val context = coroutineContext
        try {
            indexer.index(file, tocPages, reindex, embedder.takeIf { embed }, embedDim, locationSize) { done, total ->
                context.ensureActive()
                progress.onBatch(done, total)
            }
        } finally {
            searcher = null
        }
    }

    /** Embeds what [index] didn't finish (e.g. after a cancellation). */
    suspend fun embed(bookId: Long, progress: Progress = Progress { _, _ -> }): EmbedResult = locked {
        val embedder = requireNotNull(embedder) { "RagEngine has no embedder" }
        val context = coroutineContext
        try {
            indexer.embed(bookId, embedder, embedDim) { done, total ->
                context.ensureActive()
                progress.onBatch(done, total)
            }
        } finally {
            searcher = null
        }
    }

    /** [subject] limits the search to books about it (SPEC §8.5). */
    suspend fun search(query: String, k: Int = 5, bookId: Long? = null, mode: Mode = Mode.HYBRID, subject: String? = null): List<Hit> = locked {
        val s = searcher ?: Searcher(store, embedder, warn = { android.util.Log.w(TAG, it) }, keywordsOnModelMismatch = true, bitsCacheBytes = bitsCacheBytes).also { searcher = it }
        s.search(query, k, bookId, mode, subject)
    }

    suspend fun books(): List<BookRow> = locked { store.books() }

    /** What a book is about: its file's subjects, plus any from `rag subjects` on the desktop (SPEC §8.5). */
    suspend fun subjects(bookId: Long): List<BookSubject> = locked { store.subjects(bookId) }

    suspend fun deleteBook(bookId: Long) = locked { store.deleteBook(bookId).also { searcher = null } }

    /** Closes the database. The [embedder] belongs to the caller, who closes it (it may be shared). */
    override fun close() = store.close()

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private companion object {
        const val TAG = "RagEngine"
    }
}
