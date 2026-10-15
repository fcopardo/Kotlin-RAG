package rag.store

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.io.TempDir
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.search.Searcher
import rag.summarize.FakeSummarizer
import rag.summarize.SummaryBooks
import rag.summarize.SummaryIndexer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A database written by one driver reads identically with the other (SPEC §15.8). */
class DriverParityTest {

    @TempDir
    lateinit var dir: File

    private val queries = listOf("slot table fact", "Snapshots isolate state changes", "applied when ready", "nothing matches xyzzy")

    /** Everything a reader sees: books, units, summaries, and search results for a few queries. */
    private fun snapshot(store: SqliteStore): String {
        val books = store.books()
        val units = books.flatMap { store.units(it.id) }
        val summaries = books.flatMap { store.summaries(it.id) }
        val searcher = Searcher(store, FakeEmbedder())
        val hits = queries.map { q -> Json.encodeToString(searcher.search(q, k = 7)) }
        return listOf(books.map { it.copy(createdAt = "") }, units, summaries, hits, books.map { store.pageLabels(it.id) }, store.bookText(1))
            .joinToString("\n") { it.toString() }
    }

    private fun write(driver: SqlDriver): File {
        val db = File(dir, "written-by-${driver.name}.db")
        SqliteStore(db.path, driver).use { store ->
            val bookId = Indexer(store).index(SummaryBooks.build(File(dir, "book.epub")), embedder = FakeEmbedder()).bookId
            SummaryIndexer(store, FakeSummarizer(), FakeEmbedder()).summarize(bookId)
        }
        return db
    }

    @Test
    fun `jdbc and bundled read each other's files identically`() {
        listOf(JdbcSqlDriver(), BundledSqlDriver()).forEach { writer ->
            val file = write(writer)
            val viaJdbc = SqliteStore(file.path, JdbcSqlDriver()).use(::snapshot)
            val viaBundled = SqliteStore(file.path, BundledSqlDriver()).use(::snapshot)
            assertEquals(viaJdbc, viaBundled, "file written by ${writer.name}")
            assertTrue("SUMMARY" in viaJdbc && "slot table" in viaJdbc)
        }
    }

    @Test
    fun `both drivers write the same content`() {
        val a = SqliteStore(write(JdbcSqlDriver()).path, JdbcSqlDriver()).use(::snapshot)
        val b = SqliteStore(write(BundledSqlDriver()).path, BundledSqlDriver()).use(::snapshot)

        assertEquals(a, b)
    }
}
