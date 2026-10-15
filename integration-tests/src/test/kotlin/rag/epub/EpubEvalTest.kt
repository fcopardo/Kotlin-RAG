package rag.epub

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import rag.embed.OllamaEmbedder
import rag.index.Indexer
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
 * The Art of War evaluation set (SPEC §16.8) on the committed Standard Ebooks fixture, with the
 * default settings. Needs RAG_OLLAMA=1. Floors are the measured values (2026-10-14).
 */
@Tag("ollama")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EpubEvalTest {

    private lateinit var dir: File
    private lateinit var store: SqliteStore
    private val embedder = OllamaEmbedder()

    @BeforeAll
    fun index() {
        assumeTrue(System.getenv("RAG_OLLAMA") == "1", "needs RAG_OLLAMA=1")
        dir = Files.createTempDirectory("rag-epub").toFile()
        store = SqliteStore(File(dir, "aow.db").path)
        Indexer(store).index(File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub"), embedder = embedder)
    }

    @AfterAll
    fun cleanUp() {
        if (::store.isInitialized) store.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    @Test
    fun `art of war evaluation set`() {
        val questions = Evaluation.load(File("src/test/resources/eval/art-of-war-se.jsonl"))
        val s = Evaluation.summarize(Evaluation.run(Searcher(store, embedder), questions, listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)), k = 5)
            .associateBy { it.mode }
        s.values.forEach { println("art of war ${it.mode}: recall@5 %.2f MRR %.2f %s".format(it.recall, it.mrr, it.recallByKind)) }

        val hybrid = s.getValue(Mode.HYBRID)
        assertTrue(hybrid.recall >= 0.75, "hybrid recall@5 ${hybrid.recall}")
        assertEquals(1.0, hybrid.recallByKind.getValue("keyword"))
        assertTrue(s.getValue(Mode.VECTOR).recall >= 0.80)
        assertTrue(hybrid.recall >= s.getValue(Mode.LEXICAL).recall)
    }
}
