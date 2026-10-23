package rag

import org.junit.jupiter.api.io.TempDir
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.scale.SyntheticBooks
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Documents of several kinds in one database (SPEC §18.3): labels and the `--kind` filter. */
class DocumentKindTest {
    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private val embedder = FakeEmbedder()
    private var bookId = 0L
    private var paperId = 0L

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "mixed.db").path)
        val builder = SyntheticBooks(listOf(File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")))
        val indexer = Indexer(store)
        bookId = store.insertBook(builder.build(1, paragraphs = 40), "sha-book", "synthetic")
        paperId = store.insertBook(builder.build(2, paragraphs = 40, kind = DocumentKinds.PAPER.name), "sha-paper", "synthetic")
        listOf(bookId, paperId).forEach { indexer.embed(it, embedder) }
    }

    @AfterTest
    fun tearDown() = store.close()

    @Test
    fun `an indexed document keeps its kind, and old books default to book`() {
        assertEquals("book", store.book(bookId)!!.kind)
        assertEquals("paper", store.book(paperId)!!.kind)
        // A kind nobody knows is still named, and falls back to a book's level words.
        assertEquals("chapter", DocumentKinds.of("recipe").levels.section)
        assertEquals(listOf("paper", "section", "paragraph", "sentence"), DocumentKinds.PAPER.levels.let {
            listOf(it.root, it.section, it.passage, it.fragment)
        })
    }

    @Test
    fun `the kind filter scopes every search mode`() {
        val searcher = Searcher(store, embedder)
        for (mode in listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)) {
            // Both documents are built from the same sentence pool, so which one ranks first is not
            // meaningful; what matters is that each filter returns hits, and only from its own kind.
            assertTrue(searcher.search("war", k = 10, mode = mode).isNotEmpty(), "$mode: unfiltered finds something")
            val papers = searcher.search("war", k = 10, mode = mode, kind = "paper")
            val books = searcher.search("war", k = 10, mode = mode, kind = "book")
            assertTrue(papers.isNotEmpty() && papers.all { it.bookId == paperId }, "$mode: only the paper")
            assertTrue(books.isNotEmpty() && books.all { it.bookId == bookId }, "$mode: only the book")
            assertEquals(emptyList(), searcher.search("war", k = 10, mode = mode, kind = "contract"), "$mode: no contracts indexed")
        }
        // Unfiltered keyword search does rank both together (SPEC §8.4), so it sees both kinds.
        val all = searcher.search("war", k = 20, mode = Mode.LEXICAL)
        assertEquals(setOf(bookId, paperId), all.map { it.bookId }.toSet())
    }
}
