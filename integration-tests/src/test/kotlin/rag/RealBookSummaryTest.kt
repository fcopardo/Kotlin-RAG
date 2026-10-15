package rag

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import rag.embed.OllamaEmbedder
import rag.index.Indexer
import rag.index.UnitKind
import rag.search.MatchType
import rag.search.Searcher
import rag.store.SqliteStore
import rag.summarize.OllamaSummarizer
import rag.summarize.SummaryIndexer
import rag.summarize.SummaryTree
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6 on "Jetpack Compose internals" with a real summary model (SPEC §11). Needs RAG_BOOK_PDF,
 * RAG_OLLAMA=1 and RAG_SUMMARY_MODEL (e.g. llama3.2:3b, pulled). Takes several minutes.
 */
@Tag("book")
@Tag("ollama")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealBookSummaryTest {

    private lateinit var dir: File
    private lateinit var store: SqliteStore
    private val embedder = OllamaEmbedder()

    @BeforeAll
    fun summarize() {
        val pdf = System.getenv("RAG_BOOK_PDF")?.let(::File)
        val model = System.getenv("RAG_SUMMARY_MODEL")
        assumeTrue(pdf?.isFile == true && System.getenv("RAG_OLLAMA") == "1" && !model.isNullOrBlank(),
            "needs RAG_BOOK_PDF, RAG_OLLAMA=1 and RAG_SUMMARY_MODEL")
        dir = Files.createTempDirectory("rag-summary").toFile()
        store = SqliteStore(File(dir, "book.db").path)
        val bookId = Indexer(store).index(pdf!!, embedder = embedder).bookId
        SummaryIndexer(store, OllamaSummarizer(model!!), embedder).summarize(bookId)
    }

    @Test
    fun `summaries don't cost passage recall`() {
        val questions = rag.search.Evaluation.load(File("src/test/resources/eval/compose-internals.jsonl"))
        val hybrid = rag.search.Evaluation.summarize(
            rag.search.Evaluation.run(Searcher(store, embedder), questions, listOf(rag.search.Mode.HYBRID)), k = 5,
        ).single()
        println("with summaries: hybrid recall@5 %.2f".format(hybrid.recall))
        assertTrue(hybrid.recall >= 0.80, "hybrid recall@5 with summaries ${hybrid.recall}")
    }

    @AfterAll
    fun cleanUp() {
        if (::store.isInitialized) store.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun `eight chapter summaries and a book summary, within their word caps`() {
        val all = store.summaries(1)
        val caps = mapOf("section" to SummaryTree.SECTION_WORDS, "group" to SummaryTree.GROUP_WORDS,
            "chapter" to SummaryTree.CHAPTER_WORDS, "book" to SummaryTree.BOOK_WORDS)

        assertEquals(8, all.count { it.level == "chapter" })
        assertEquals(1, all.count { it.level == "book" })
        all.forEach { s -> assertTrue(s.text.split(Regex("\\s+")).size <= caps.getValue(s.level), "${s.level} #${s.ordinal} too long") }
    }

    @Test
    fun `summaries name APIs that are in the book`() {
        val text = store.bookText(1)
        val names = store.summaries(1).flatMap { s -> Regex("\\b[A-Z][a-z]+(?:[A-Z][a-z0-9]*)+\\b").findAll(s.text).map { it.value } }
        val known = names.count { it in text }
        println("grounding: $known of ${names.size} CamelCase names found in the book")
        assertTrue(names.isEmpty() || known >= 0.95 * names.size, "invented names: ${names.filterNot { it in text }.distinct()}")
    }

    @Test
    fun `chapter- and book-level questions find summaries`() {
        val searcher = Searcher(store, embedder)
        val snapshot = searcher.search("what is the state snapshot system chapter about", k = 3)
        assertTrue(snapshot.take(3).any { it.matchType == MatchType.SUMMARY && it.chapter.title == "5. State snapshot system" }, snapshot.map { it.matchType to it.chapter.title }.toString())

        // #2 since front matter is down-weighted and summaries are added on top of passages (was #5).
        val cover = searcher.search("what does this book cover", k = 3)
        assertTrue(cover.take(3).any { it.summaryLevel == "book" }, cover.map { it.matchType to it.chapter.title }.toString())
        assertEquals(store.units(1, UnitKind.CHAPTER).size, 9)
    }
}
