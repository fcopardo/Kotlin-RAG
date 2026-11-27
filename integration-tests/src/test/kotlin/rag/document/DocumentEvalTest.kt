package rag.document

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import rag.BookReader
import rag.embed.OllamaEmbedder
import rag.extract.Normalizer
import rag.index.Indexer
import rag.search.EvalQuestion
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
 * The paper and contract evaluation sets (SPEC §18.6): per kind, a committed fixture folder indexed into its own
 * database, a 20-question set (10 keyword, 10 paraphrase) answered by one passage of a named section, and the
 * reviewed section titles of every document.
 *
 * Fixtures (`src/test/resources/documents`): `papers/` is a frozen snapshot of this repository's guides in three
 * formats (Markdown, HTML and Word, the last two made with pandoc from the Markdown kept in `sources/`);
 * `contracts/` holds two agreements written for these tests, one plain text and one Word. Floors are measured values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class DocumentEvalTest {
    private class Kind(val name: String, val lexicalFloor: Double, val hybridFloor: Double) {
        val folder = File("src/test/resources/documents/${name}s")
        val questions: List<EvalQuestion> = Evaluation.load(File("src/test/resources/eval/${name}s.jsonl"))
        lateinit var store: SqliteStore
    }

    private val kinds = listOf(Kind("paper", PAPER_LEXICAL_FLOOR, PAPER_HYBRID_FLOOR), Kind("contract", CONTRACT_LEXICAL_FLOOR, CONTRACT_HYBRID_FLOOR))
    private lateinit var dir: File

    @BeforeAll
    fun index() {
        dir = Files.createTempDirectory("rag-document-eval").toFile()
        for (k in kinds) {
            k.store = SqliteStore(File(dir, "${k.name}.db").path)
            Indexer(k.store).indexFolder(k.folder)
        }
    }

    @AfterAll
    fun cleanUp() {
        kinds.forEach { if (it.isStoreReady()) it.store.close() }
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun Kind.isStoreReady() = runCatching { store }.isSuccess

    private fun documents(k: Kind) = k.folder.listFiles()!!.filter { it.isFile }.sortedBy { it.name }

    @Test
    fun `each set has 20 questions, half keyword and half paraphrase`() {
        for (k in kinds) {
            assertEquals(20, k.questions.size, k.name)
            assertEquals(mapOf("keyword" to 10, "paraphrase" to 10), k.questions.groupingBy { it.kind }.eachCount(), k.name)
        }
    }

    @Test
    fun `every document is indexed as its folder's kind`() {
        for (k in kinds) {
            val books = k.store.books()
            assertEquals(documents(k).size, books.size, "${k.name}: one document per file")
            books.forEach { assertEquals(k.name, it.kind, "${it.title} is a ${it.kind}") }
        }
    }

    @Test
    fun `the section titles match the reviewed golden`() {
        val actual = kinds.flatMap { k ->
            documents(k).flatMap { file ->
                val book = BookReader().read(file)
                listOf("== ${k.folder.name}/${file.name}: ${book.kind}") + book.chapters.filter { it.number > 0 }.map { it.title }
            }
        }
        val golden = File("src/test/resources/documents/structure.golden.txt")
        File("build/documents.structure.txt").writeText(actual.joinToString("\n") + "\n")
        assertEquals(golden.readText().trimEnd().split("\n"), actual, "sections changed; the new list is in build/documents.structure.txt")
    }

    @Test
    fun `every question points at a passage of its expected section`() {
        for (k in kinds) {
            val passages = documents(k).flatMap { file ->
                val book = BookReader().read(file)
                book.segments.flatMap { s -> s.paragraphs.map { s.chapter.title to book.book.text.substring(it.start, it.end + 1) } }
            }
            for (q in k.questions) {
                val anchor = Normalizer.hashNormalize(q.expectedAnchor!!)
                val inSection = passages.filter { it.first == q.expectedChapter }
                assertTrue(inSection.isNotEmpty(), "${k.name} ${q.id}: no section titled ${q.expectedChapter}")
                assertTrue(inSection.any { anchor in Normalizer.hashNormalize(it.second) }, "${k.name} ${q.id}: '${q.expectedAnchor}' is in no passage of ${q.expectedChapter}")
                assertEquals(1, passages.count { anchor in Normalizer.hashNormalize(it.second) }, "${k.name} ${q.id}: the anchor must name one passage")
            }
        }
    }

    @Test
    fun `lexical search answers the keyword questions`() {
        for (k in kinds) {
            val s = Evaluation.summarize(Evaluation.run(Searcher(k.store, null), k.questions, listOf(Mode.LEXICAL)), k = 5).single()
            println("${k.name} eval LEXICAL: recall@5 %.2f MRR %.2f %s".format(s.recall, s.mrr, s.recallByKind))
            assertEquals(1.0, s.recallByKind.getValue("keyword"), "${k.name} keyword recall@5 ${s.recallByKind}")
            assertTrue(s.recall >= k.lexicalFloor, "${k.name} lexical recall@5 ${s.recall}")
        }
    }

    @Test
    @Tag("ollama")
    @Timeout(900)
    fun `hybrid and vector search, with Ollama`() {
        assumeTrue(System.getenv("RAG_OLLAMA") == "1", "needs RAG_OLLAMA=1")
        val embedder = OllamaEmbedder()
        for (k in kinds) {
            k.store.books().forEach { Indexer(k.store).embed(it.id, embedder) }
            val s = Evaluation.summarize(Evaluation.run(Searcher(k.store, embedder), k.questions, listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)), k = 5)
                .associateBy { it.mode }
            s.values.forEach { println("${k.name} eval ${it.mode}: recall@5 %.2f MRR %.2f %s".format(it.recall, it.mrr, it.recallByKind)) }
            val hybrid = s.getValue(Mode.HYBRID)
            assertTrue(hybrid.recall >= k.hybridFloor, "${k.name} hybrid recall@5 ${hybrid.recall}")
            assertTrue(hybrid.recall >= s.getValue(Mode.LEXICAL).recall, "${k.name}: hybrid ${hybrid.recall} below lexical")
            assertEquals(1.0, hybrid.recallByKind.getValue("keyword"), k.name)
        }
    }

    private companion object {
        /** Measured 2026-11-27: lexical recall@5 0.70 (keyword 1.00, paraphrase 0.40), MRR 0.61. */
        const val PAPER_LEXICAL_FLOOR = 0.70
        /** Measured 2026-11-27: lexical recall@5 0.65 (keyword 1.00, paraphrase 0.30), MRR 0.62. */
        const val CONTRACT_LEXICAL_FLOOR = 0.65
        /** Measured 2026-11-27 with mxbai-embed-large: hybrid recall@5 0.95 (paraphrase 0.90), MRR 0.74; vector 0.90. */
        const val PAPER_HYBRID_FLOOR = 0.95
        /** Measured 2026-11-27 with mxbai-embed-large: hybrid recall@5 0.95 (paraphrase 0.90), MRR 0.93; vector 1.00. */
        const val CONTRACT_HYBRID_FLOOR = 0.95
    }
}
