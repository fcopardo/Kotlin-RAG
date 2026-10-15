package rag.server

import org.junit.jupiter.api.io.TempDir
import rag.SampleBook
import rag.embed.FakeEmbedder
import rag.extract.TestPdfs
import rag.index.Indexer
import rag.store.SqliteStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RagServiceTest {
    @TempDir
    lateinit var dir: File
    private lateinit var db: File
    private lateinit var store: SqliteStore
    private lateinit var service: RagService
    private var bookId = 0L

    @BeforeTest
    fun setUp() {
        db = File(dir, "rag.db")
        store = SqliteStore(db.path)
        bookId = Indexer(store).index(SampleBook.write(File(dir, "sample.pdf")), embedder = FakeEmbedder()).bookId
        service = RagService(store, { FakeEmbedder(model = it) })
    }

    @AfterTest
    fun tearDown() = store.close()

    @Test
    fun `passages carry book, chapter, pages and the text to quote`() {
        val passage = service.passages("Text about delta on this page", k = 1).single()

        assertEquals("Riding Fast", passage.chapter)
        assertEquals("p. 8", passage.pages) // PDF page 8: the sample has no page labels
        assertTrue("delta" in passage.text && "delta" in passage.bestSentence, passage.toString())
    }

    @Test
    fun `a book indexed by another process is searched without restarting`() {
        service.search("bravo") // warms the caches
        SqliteStore(db.path).use { other ->
            val pdf = File(dir, "tea.pdf")
            TestPdfs.build(listOf(listOf("Tea", "", "Oolong tea leaves are rolled and oxidized.")), listOf("Tea" to 1)).use { it.save(pdf) }
            Indexer(other).index(pdf, embedder = FakeEmbedder())
        }

        val hits = service.search("oolong tea leaves", k = 1, mode = rag.search.Mode.VECTOR)

        assertEquals("Oolong tea leaves are rolled and oxidized.", hits.single().paragraph.text)
    }

    @Test
    fun `chapters list, and long chapters read in pages`() {
        val chapters = service.chapters(bookId)
        assertEquals(listOf("Front matter", "Introduction", "Riding Fast", "Repairs"), chapters.map { it.title })

        val first = service.readChapter(bookId, 2, maxChars = 60)
        val rest = generateSequence(first) { page -> page.nextOffset?.let { service.readChapter(bookId, 2, it, maxChars = 60) } }.toList()
        assertTrue(rest.size > 1)
        assertNull(rest.last().nextOffset)
        assertEquals(first.totalChars, rest.sumOf { it.text.length })
    }

    @Test
    fun `bad requests say what's wrong`() {
        assertFailsWith<NoSuchElementException> { service.chapters(99) }
        assertFailsWith<NoSuchElementException> { service.readChapter(bookId, 42) }
        assertFailsWith<IllegalArgumentException> { service.search(" ") }
        assertFailsWith<IllegalArgumentException> { service.search("bikes", k = 0) }
    }
}
