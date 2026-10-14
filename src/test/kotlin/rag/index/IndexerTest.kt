package rag.index

import org.junit.jupiter.api.io.TempDir
import rag.RagException
import rag.SampleBook
import rag.embed.Embedder
import rag.embed.FakeEmbedder
import rag.extract.Normalizer
import rag.store.SqliteStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexerTest {

    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private lateinit var pdf: File

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "rag.db").path)
        pdf = SampleBook.write(File(dir, "bike.pdf"))
    }

    @AfterTest
    fun tearDown() = store.close()

    private fun indexer(batchSize: Int = 32) = Indexer(store, batchSize = batchSize)

    @Test
    fun `indexes structure with offsets into the only copy of the text`() {
        val bookId = indexer().index(pdf).bookId

        val text = store.bookText(bookId)
        val units = store.units(bookId)
        assertEquals(listOf(1, 4), listOf(UnitKind.BOOK, UnitKind.CHAPTER).map { k -> units.count { it.kind == k } })
        units.forEach { u ->
            val unitText = text.substring(u.start, u.end + 1)
            assertEquals(u.hash, Normalizer.hash(unitText), "hash of ${u.key}")
        }
        assertEquals(listOf("Front matter", "Introduction", "Riding Fast", "Repairs"),
            store.units(bookId, UnitKind.CHAPTER).map { it.title })
    }

    @Test
    fun `every unit is found again by its key`() {
        val bookId = indexer().index(pdf).bookId

        store.units(bookId).forEach { u ->
            assertEquals(listOf(u.id), store.findByKey(u.key, bookId).map { it.id }, u.key)
        }
        assertEquals(emptyList(), store.findByKey("not-a-key"))
    }

    @Test
    fun `only book and summary tables store text, and FTS is contentless`() {
        indexer().index(pdf)

        val textTables = tables().filter { t -> columns(t).contains("text") }
        assertEquals(setOf("book", "summary"), textTables.toSet())
        // Contentless FTS returns NULL for its column but still matches.
        val hit = store.ftsMatch("para_fts", "\"about foxtrot\"").single()
        assertEquals("Text about foxtrot on this page.", store.unit(hit)!!.let { store.bookText(1).substring(it.start, it.end + 1) })
        store.connection.createStatement().use { s ->
            s.executeQuery("SELECT text FROM para_fts LIMIT 1").use { rs -> rs.next(); assertNull(rs.getString(1)) }
        }
    }

    @Test
    fun `a new database records schema version 1`() {
        val version = store.connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }

        assertEquals(SqliteStore.SCHEMA_VERSION, version)
    }

    @Test
    fun `an EPUB indexes and searches next to a PDF, with location labels`() {
        indexer().index(pdf, embedder = FakeEmbedder())
        val epub = rag.epub.TestEpubs.build(File(dir, "soup.epub"), listOf(
            rag.epub.TestEpubs.Doc("ch1.xhtml", "<h1>Soup</h1><p>The soup was cold when it arrived. Nobody complained about the temperature.</p>"),
            rag.epub.TestEpubs.Doc("ch2.xhtml", "<h1>Bread</h1><p>Bread was served warm with butter.</p>"),
        ), listOf(rag.epub.TestEpubs.Toc("Soup", "ch1.xhtml"), rag.epub.TestEpubs.Toc("Bread", "ch2.xhtml")))

        val epubId = indexer().index(epub, embedder = FakeEmbedder(), locationSize = 40).bookId

        val row = store.book(epubId)!!
        assertEquals(Triple("epub", "location", "nav"), Triple(row.format, row.pageKind, row.chapterStrategy))
        assertTrue(store.pageLabels(epubId).all { it.startsWith("loc ") })
        val hit = rag.search.Searcher(store, FakeEmbedder()).search("Nobody complained about the temperature.").first()
        assertEquals(epubId, hit.bookId)
        assertEquals("location", hit.pageKind)
        assertTrue(hit.phrase.pageStartLabel.startsWith("loc "))
        assertEquals(2, store.books().size)
    }

    @Test
    fun `indexing the same file again inserts nothing`() {
        val first = indexer().index(pdf)
        val units = store.count("unit")

        val second = indexer().index(pdf)

        assertTrue(second.skipped)
        assertEquals(first.bookId, second.bookId)
        assertEquals(units, store.count("unit"))
        assertEquals(1, store.books().size)
    }

    @Test
    fun `reindex rebuilds without orphaned rows`() {
        indexer().index(pdf, embedder = FakeEmbedder())
        val counts = listOf("unit", "para_fts", "phrase_fts", "embedding", "page").associateWith(store::count)

        val again = indexer().index(pdf, reindex = true, embedder = FakeEmbedder())

        assertTrue(!again.skipped)
        assertEquals(counts, counts.keys.associateWith(store::count))
        assertEquals(1, store.books().size)
    }

    @Test
    fun `embeds every paragraph as int8 plus bits at the requested dimension`() {
        val result = indexer().index(pdf, embedder = FakeEmbedder(dim = 256), embedDim = 128)

        val book = store.book(result.bookId)!!
        assertEquals("complete", book.embedStatus)
        assertEquals(128, book.embedDim)
        val paragraphs = store.units(result.bookId, UnitKind.PARAGRAPH)
        val vectors = store.vectors(paragraphs.map { it.id })
        assertEquals(paragraphs.size, vectors.size)
        vectors.forEach { assertEquals(128, it.int8.size); assertEquals(2, it.bits.size) }
        assertEquals(paragraphs.size, store.binaryVectors(result.bookId).size)
    }

    @Test
    fun `identical paragraphs are embedded once`() {
        val repeated = listOf("This exact paragraph repeats on every chapter page.", "")
        SampleBook.write(pdf, extra = repeated)
        val embedder = FakeEmbedder()

        val result = indexer().index(pdf, embedder = embedder)

        val inputs = embedder.documentCalls.flatten()
        assertEquals(1, inputs.count { "This exact paragraph repeats" in it })
        assertTrue(result.embedded!!.copied >= 7, "copies: ${result.embedded}")
        assertEquals(0, store.paragraphsMissingEmbedding(result.bookId).size)
    }

    @Test
    fun `an interrupted embedding is resumed for the missing paragraphs only`() {
        val failing = FailingAfter(1, FakeEmbedder())
        val indexer = indexer(batchSize = 4)
        assertFailsWith<RagException.EmbeddingBackendUnavailable> { indexer.index(pdf, embedder = failing) }
        val bookId = store.books().single().id
        assertEquals("partial", store.book(bookId)!!.embedStatus)
        val missing = store.paragraphsMissingEmbedding(bookId).size
        assertTrue(missing > 0)

        val resume = FakeEmbedder()
        indexer.embed(bookId, resume)

        assertEquals("complete", store.book(bookId)!!.embedStatus)
        assertEquals(missing, resume.documentCalls.flatten().size)
    }

    @Test
    fun `an unavailable backend commits nothing`() {
        val down = object : Embedder by FakeEmbedder() {
            override fun checkAvailable() = throw RagException.EmbeddingBackendUnavailable("down")
        }

        assertFailsWith<RagException.EmbeddingBackendUnavailable> { indexer().index(pdf, embedder = down) }

        assertEquals(0, store.books().size)
    }

    @Test
    fun `switching model or dimension drops old vectors`() {
        val bookId = indexer().index(pdf, embedder = FakeEmbedder(model = "a")).bookId
        val count = store.count("embedding")

        indexer().embed(bookId, FakeEmbedder(model = "b"), embedDim = 64)

        assertEquals(count, store.count("embedding"))
        assertEquals("b" to 64, store.book(bookId)!!.let { it.embedModel to it.embedDim })
        assertTrue(store.vectors(store.units(bookId, UnitKind.PARAGRAPH).map { it.id }).all { it.int8.size == 64 })
    }

    private fun tables(): List<String> = store.connection.createStatement().use { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND sql NOT LIKE 'CREATE VIRTUAL%' AND name NOT LIKE '%fts%'")
            .use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun columns(table: String): List<String> = store.connection.createStatement().use { s ->
        s.executeQuery("PRAGMA table_info($table)").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
    }

    /** Embeds [okCalls] batches, then fails like an unreachable Ollama. */
    private class FailingAfter(private val okCalls: Int, private val delegate: Embedder) : Embedder by delegate {
        private var calls = 0
        override fun embedDocuments(texts: List<String>): List<FloatArray> {
            if (calls++ >= okCalls) throw RagException.EmbeddingBackendUnavailable("Ollama went away")
            return delegate.embedDocuments(texts)
        }
    }
}
