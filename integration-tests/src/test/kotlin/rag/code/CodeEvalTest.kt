package rag.code

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import rag.BookReader
import rag.embed.OllamaEmbedder
import rag.extract.Normalizer
import rag.index.Indexer
import rag.index.UnitKind
import rag.search.Evaluation
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The code evaluation set (SPEC §18.6): 20 questions, 10 keyword and 10 paraphrase, over a frozen snapshot of
 * this repository's `core` module (`src/test/resources/code/core-snapshot`). Floors are measured values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeEvalTest {
    private val snapshot = File("src/test/resources/code/core-snapshot")
    private val questions = Evaluation.load(File("src/test/resources/eval/core-code.jsonl"))
    private lateinit var dir: File
    private lateinit var store: SqliteStore

    @BeforeAll
    fun index() {
        dir = Files.createTempDirectory("rag-code-eval").toFile()
        store = SqliteStore(File(dir, "core.db").path)
        Indexer(store).index(snapshot)
    }

    @AfterAll
    fun cleanUp() {
        if (::store.isInitialized) store.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun `the set has 20 questions, half keyword and half paraphrase`() {
        assertEquals(20, questions.size)
        assertEquals(mapOf("keyword" to 10, "paraphrase" to 10), questions.groupingBy { it.kind }.eachCount())
    }

    @Test
    fun `every question points at a passage of its expected section`() {
        val book = BookReader().read(snapshot)
        val text = book.book.text
        for (q in questions) {
            val anchor = Normalizer.hashNormalize(q.expectedAnchor!!)
            val inSection = book.segments.filter { it.chapter.title == q.expectedChapter }
            assertTrue(inSection.isNotEmpty(), "${q.id}: no section titled ${q.expectedChapter}")
            assertTrue(
                inSection.any { s -> s.paragraphs.any { anchor in Normalizer.hashNormalize(text.substring(it.start, it.end + 1)) } },
                "${q.id}: '${q.expectedAnchor}' is in no passage of ${q.expectedChapter}",
            )
        }
    }

    @Test
    fun `stored units point at exact, trimmed text inside their parents`() {
        val bookId = store.books().single().id
        val text = store.bookText(bookId)
        val units = store.units(bookId)
        val byId = units.associateBy { it.id }
        for (u in units) {
            assertTrue(u.start in 0..u.end && u.end < text.length, "unit ${u.id} span ${u.start}..${u.end}")
            if (u.kind == UnitKind.PARAGRAPH || u.kind == UnitKind.PHRASE) {
                val slice = text.substring(u.start, u.end + 1)
                assertEquals(slice.trim(), slice, "unit ${u.id} has no leading or trailing whitespace")
                assertEquals(Normalizer.hash(slice), u.hash, "unit ${u.id}'s hash is its text's")
            }
            u.parentId?.let { p ->
                val parent = byId.getValue(p)
                assertTrue(u.start >= parent.start && u.end <= parent.end, "unit ${u.id} lies inside its parent ${parent.id}")
            }
        }
    }

    @Test
    fun `lexical search answers the keyword questions`() {
        val s = Evaluation.summarize(Evaluation.run(Searcher(store, null), questions, listOf(Mode.LEXICAL)), k = 5).single()
        println("code eval LEXICAL: recall@5 %.2f MRR %.2f %s".format(s.recall, s.mrr, s.recallByKind))
        assertEquals(1.0, s.recallByKind.getValue("keyword"), "keyword recall@5 ${s.recallByKind}")
        assertTrue(s.recall >= LEXICAL_FLOOR, "lexical recall@5 ${s.recall}")
    }

    @Test
    @Tag("ollama")
    fun `hybrid and vector search, with Ollama`() {
        assumeTrue(System.getenv("RAG_OLLAMA") == "1", "needs RAG_OLLAMA=1")
        val embedder = OllamaEmbedder()
        Indexer(store).embed(store.books().single().id, embedder)
        val s = Evaluation.summarize(Evaluation.run(Searcher(store, embedder), questions, listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)), k = 5)
            .associateBy { it.mode }
        s.values.forEach { println("code eval ${it.mode}: recall@5 %.2f MRR %.2f %s".format(it.recall, it.mrr, it.recallByKind)) }
        // Not measured yet (Ollama was not running on 2026-11-23): only what must hold anyway. Record the
        // printed scores in SPEC §18.8 and turn them into floors.
        val hybrid = s.getValue(Mode.HYBRID)
        assertTrue(hybrid.recall >= s.getValue(Mode.LEXICAL).recall, "hybrid ${hybrid.recall} below lexical")
        assertEquals(1.0, hybrid.recallByKind.getValue("keyword"))
    }

    private companion object {
        /** Measured 2026-11-23: lexical recall@5 0.85 (keyword 1.00, paraphrase 0.70), MRR 0.65. */
        const val LEXICAL_FLOOR = 0.85
    }
}
