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
 * @param embedder for vector search; null gives keyword-only indexes and search.
 */
class RagEngine(
    context: Context,
    dbFile: File,
    private val embedder: Embedder? = null,
    private val embedDim: Int = Indexer.DEFAULT_DIM,
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
        progress: Progress = Progress { _, _ -> },
    ): IndexResult = locked {
        val context = coroutineContext
        try {
            indexer.index(file, tocPages, reindex, embedder, embedDim, locationSize) { done, total ->
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

    suspend fun search(query: String, k: Int = 5, bookId: Long? = null, mode: Mode = Mode.HYBRID): List<Hit> = locked {
        val s = searcher ?: Searcher(store, embedder, warn = { android.util.Log.w(TAG, it) }).also { searcher = it }
        s.search(query, k, bookId, mode)
    }

    suspend fun books(): List<BookRow> = locked { store.books() }

    suspend fun deleteBook(bookId: Long) = locked { store.deleteBook(bookId).also { searcher = null } }

    override fun close() {
        store.close()
        (embedder as? AutoCloseable)?.close()
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private companion object {
        const val TAG = "RagEngine"
    }
}
