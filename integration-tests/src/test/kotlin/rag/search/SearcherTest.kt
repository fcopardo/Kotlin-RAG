package rag.search

import org.junit.jupiter.api.io.TempDir
import rag.RagException
import rag.embed.Embedder
import rag.embed.FakeEmbedder
import rag.extract.TestPdfs
import rag.index.Indexer
import rag.index.UnitKeys
import rag.store.SqliteStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearcherTest {

    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private var bookId = 0L
    private val embedder = FakeEmbedder()

    /** Two one-page chapters with multi-sentence paragraphs. */
    private val pages = listOf(
        listOf("Bikes", "", "The bike went really fast. Dr. Lee watched from the hill.", "", "Gears let riders climb steep hills with less effort."),
        listOf("Soup", "", "The soup was cold when it arrived. Nobody complained about the temperature.", "", "Bread was served warm with butter."),
    )

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "rag.db").path)
        bookId = index("food.pdf", pages, listOf("Bikes" to 1, "Soup" to 2))
    }

    @AfterTest
    fun tearDown() = store.close()

    private fun index(name: String, pages: List<List<String>>, outline: List<Pair<String, Int>>): Long {
        val pdf = File(dir, name)
        TestPdfs.build(pages, outline).use { it.save(pdf) }
        return Indexer(store).index(pdf, embedder = embedder).bookId
    }

    private fun searcher(e: Embedder? = embedder, warnings: MutableList<String> = mutableListOf()) =
        Searcher(store, e, warn = { warnings += it })

    @Test
    fun `an exact sentence is an EXACT_PHRASE hit with its paragraph and chapter`() {
        val hit = searcher().search("Nobody complained about the temperature.").first()

        assertEquals(MatchType.EXACT_PHRASE, hit.matchType)
        assertEquals(1.0, hit.score)
        assertEquals("Nobody complained about the temperature.", hit.phrase.text)
        assertEquals("The soup was cold when it arrived. Nobody complained about the temperature.", hit.paragraph.text)
        assertEquals("Soup" to 2, hit.chapter.title to hit.paragraph.pageStart)
        assertEquals(listOf(hit.phrase.text), store.findByKey(hit.phrase.unitKey, bookId).map { store.bookText(bookId).substring(it.start, it.end + 1) })
        assertTrue(UnitKeys.parse(hit.chapter.unitKey) != null)
    }

    @Test
    fun `exact match ignores case and spacing, and a one-sentence paragraph appears once`() {
        val hits = searcher().search("gears let riders  CLIMB steep hills with less effort.", k = 5)

        assertEquals(MatchType.EXACT_PARAGRAPH, hits.first().matchType)
        assertEquals(1, hits.count { it.paragraph.text.startsWith("Gears") })
    }

    @Test
    fun `keyword search finds the paragraph and its closest phrase`() {
        val hit = searcher().search("complained temperature", mode = Mode.LEXICAL).first()

        assertEquals(MatchType.LEXICAL, hit.matchType)
        assertEquals("Nobody complained about the temperature.", hit.phrase.text)
    }

    @Test
    fun `vector search finds the paragraph`() {
        val hit = searcher().search("riders climb hills", mode = Mode.VECTOR).first()

        assertEquals(MatchType.VECTOR, hit.matchType)
        assertTrue(hit.paragraph.text.startsWith("Gears"))
    }

    @Test
    fun `hybrid marks results found by both paths`() {
        val hit = searcher().search("cold soup arrived").first()

        assertEquals(MatchType.HYBRID, hit.matchType)
        assertTrue(hit.paragraph.text.startsWith("The soup was cold"))
    }

    @Test
    fun `results are distinct paragraphs, at most k`() {
        val hits = searcher().search("the", k = 3)

        assertTrue(hits.size <= 3)
        assertEquals(hits.size, hits.map { it.paragraph.unitKey }.toSet().size)
    }

    @Test
    fun `fts syntax in the query is harmless`() {
        searcher().search("AND OR NOT \"( * : NEAR(", mode = Mode.LEXICAL)
        searcher().search("\"\"\"", mode = Mode.HYBRID)
    }

    @Test
    fun `searching with another model fails with exit code 5`() {
        val error = assertFailsWith<RagException.ModelMismatch> { searcher(FakeEmbedder(model = "other")).search("soup") }

        assertEquals(5, error.exitCode)
    }

    @Test
    fun `hybrid falls back to keywords when the backend is down, vector mode fails`() {
        val down = object : Embedder by embedder {
            override fun embedQuery(text: String) = throw RagException.EmbeddingBackendUnavailable("Ollama is down")
        }
        val warnings = mutableListOf<String>()

        val hits = searcher(down, warnings).search("cold soup")

        assertTrue(hits.isNotEmpty() && hits.all { it.matchType == MatchType.LEXICAL })
        assertEquals(1, warnings.size)
        assertFailsWith<RagException.EmbeddingBackendUnavailable> { searcher(down).search("cold soup", mode = Mode.VECTOR) }
    }

    @Test
    fun `search can be limited to one book`() {
        val other = index("other.pdf", listOf(listOf("Soup", "", "Another book also mentions soup and bread.")), emptyList())

        val all = searcher().search("soup bread", k = 10, mode = Mode.LEXICAL).map { it.bookId }.toSet()
        val only = searcher().search("soup bread", k = 10, bookId = other, mode = Mode.LEXICAL).map { it.bookId }.toSet()

        assertEquals(setOf(bookId, other), all)
        assertEquals(setOf(other), only)
    }
}
