package rag.summarize

import org.junit.jupiter.api.io.TempDir
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubjectIndexerTest {
    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private val embedder = FakeEmbedder()
    private val epubs = File("src/test/resources/epub")
    private var warId = 0L
    private var cookbookId = 0L

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "rag.db").path)
        val indexer = Indexer(store)
        warId = indexer.index(epubs.resolve("sun-tzu_the-art-of-war_lionel-giles.epub"), embedder = embedder).bookId
        cookbookId = indexer.index(SummaryBooks.build(File(dir, "book.epub")), embedder = embedder).bookId
    }

    @AfterTest
    fun tearDown() = store.close()

    @Test
    fun `an EPUB's own subjects are stored at index time, with vectors once embedded`() {
        assertEquals(
            listOf(BookSubject("Military art and science", Subjects.MAIN, "metadata"), BookSubject("War", Subjects.MAIN, "metadata")),
            store.subjects(warId),
        )
        assertEquals(emptyList(), store.subjectsMissingVector(warId, embedder.model, 512))
        assertTrue(store.bookVectors(embedder.model).keys.containsAll(listOf(warId, cookbookId)))
    }

    @Test
    fun `the model's subjects are stored, embedded and listed with the book's own`() {
        val summarizer = FakeSummarizer(completion = "Main: Jetpack Compose\nTangential: Kotlin\nBroad: Programming")

        val subjects = SubjectIndexer(store, summarizer, listOf(embedder to 512)).describe(cookbookId)

        assertEquals(listOf("Jetpack Compose", "Kotlin", "Programming"), subjects.map { it.subject })
        assertTrue("Chapters:" in summarizer.prompts.single())
        assertEquals(subjects, store.subjects(cookbookId))
        assertEquals(emptyList(), store.subjectsMissingVector(cookbookId, embedder.model, 512))
        // Asking again replaces the model's subjects; the file's own stay.
        SubjectIndexer(store, FakeSummarizer(completion = "Main: Compose runtime")).describe(warId)
        assertEquals(listOf("Compose runtime", "Military art and science", "War"), store.subjects(warId).map { it.subject })
    }

    @Test
    fun `a subject filter keeps the search to matching books`() {
        SubjectIndexer(store, FakeSummarizer(completion = "Main: Jetpack Compose\nTangential: Kotlin")).describe(cookbookId)
        val searcher = Searcher(store, embedder = null)

        assertEquals(setOf(warId), searcher.booksAbout("war"))
        assertEquals(setOf(cookbookId), searcher.booksAbout("kotlin"))
        assertEquals(emptySet(), searcher.booksAbout("gardening"))
        val hits = searcher.search("state", k = 10, mode = Mode.LEXICAL, subject = "war")
        assertTrue(hits.isNotEmpty() && hits.all { it.bookId == warId }, hits.map { it.bookId }.toString())
        assertEquals(emptyList(), searcher.search("state", k = 10, mode = Mode.LEXICAL, subject = "gardening"))
    }
}
