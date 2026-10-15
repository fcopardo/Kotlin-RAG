package rag

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import rag.embed.OllamaEmbedder
import rag.embed.Vectors
import rag.index.Indexer
import rag.index.UnitKind
import rag.search.Evaluation
import rag.search.MatchType
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Search on "Jetpack Compose internals" with the real embedding model. Needs RAG_BOOK_PDF and
 * RAG_OLLAMA=1. The book is indexed once for the class (about 20 seconds).
 */
@Tag("book")
@Tag("ollama")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealBookSearchTest {

    private lateinit var dir: File
    private lateinit var store: SqliteStore
    private val embedder = OllamaEmbedder()

    @BeforeAll
    fun indexBook() {
        val pdf = System.getenv("RAG_BOOK_PDF")?.let(::File)
        assumeTrue(pdf?.isFile == true && System.getenv("RAG_OLLAMA") == "1", "needs RAG_BOOK_PDF and RAG_OLLAMA=1")
        dir = Files.createTempDirectory("rag-book").toFile()
        store = SqliteStore(File(dir, "book.db").path)
        Indexer(store).index(pdf!!, embedder = embedder)
    }

    @AfterAll
    fun cleanUp() {
        if (::store.isInitialized) store.close()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun search(q: String, mode: Mode = Mode.HYBRID, k: Int = 5) = Searcher(store, embedder).search(q, k, mode = mode)

    @Test
    fun `a sentence copied from the book is an exact phrase hit on its page`() {
        val hit = search("This parameter is implicit, the developer remains agnostic of it.").first()

        assertEquals(MatchType.EXACT_PHRASE, hit.matchType)
        assertEquals(13, hit.phrase.pageStart)
        assertEquals("1. Composable functions", hit.chapter.title)
    }

    @Test
    fun `questions land in the right chapter`() {
        val expected = mapOf(
            "what data structure stores the composition state" to "3. The Compose runtime",
            "which thread runs recomposition" to "3. The Compose runtime",
            "why must composable functions avoid side effects" to "1. Composable functions",
            "how are modifiers chained together on a layout node" to "4. Compose UI",
            "what is a snapshot and how does it isolate state changes" to "5. State snapshot system",
        )
        expected.forEach { (q, chapter) ->
            val top3 = search(q, k = 3).map { it.chapter.title }
            assertTrue(chapter in top3, "'$q' → $top3")
        }
    }

    /**
     * How much the compact vectors (Matryoshka 512 dims, binary first pass, int8 rescoring) agree
     * with exact float search at the model's full size: average top-10 overlap over paraphrase
     * questions. The floor catches regressions; see SPEC §11 for the measured values.
     */
    @Test
    fun `compact vectors stay close to full float search`() {
        val queries = listOf(
            "how does the framework decide which UI functions to run again when data changes",
            "why must composable functions avoid side effects",
            "what data structure stores the composition state",
            "how are modifiers chained together on a layout node",
            "what is a snapshot and how does it isolate state changes",
            "when does a remembered value get forgotten",
            "how does the compiler make lambdas cheaper",
            "what problem do appliers solve",
            "how is drawing done after layout",
            "which thread runs recomposition",
        )
        val text = store.bookText(1)
        val paragraphs = store.units(1, UnitKind.PARAGRAPH)
        val titles = store.units(1, UnitKind.CHAPTER).associate { it.id to it.title.orEmpty() }
        val full = embedder.embedDocuments(paragraphs.map { p ->
            titles[p.parentId] + "\n" + text.substring(p.start, p.end + 1).let { if (p.isCode) it else it.replace('\n', ' ') }
        })

        val overlap = queries.map { q ->
            val qv = embedder.embedQuery(q)
            val exact = paragraphs.indices.sortedByDescending { Vectors.dot(qv, full[it]) }.take(10).map { paragraphs[it].key }.toSet()
            val compact = search(q, Mode.VECTOR, k = 10).map { it.paragraph.unitKey }
            compact.count { it in exact } / 10.0
        }.average()

        println("${embedder.model}: compact ${Indexer.DEFAULT_DIM} dims vs float ${embedder.dim} overlap@10: %.2f".format(overlap))
        assertTrue(overlap >= OVERLAP_FLOOR, "overlap@10 = $overlap")
    }

    /**
     * The evaluation set (SPEC §11): 20 questions with known answer pages, 9 of them paraphrases
     * sharing no content words with their answer. Floors are the values measured with the default
     * model, so a drop fails the build.
     */
    @Test
    fun `evaluation set recall`() {
        val questions = Evaluation.load(File("src/test/resources/eval/compose-internals.jsonl"))
        val summaries = Evaluation.summarize(
            Evaluation.run(Searcher(store, embedder), questions, listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)), k = 5,
        ).associateBy { it.mode }
        summaries.values.forEach { println("${embedder.model} ${it.mode}: recall@5 %.2f MRR %.2f %s".format(it.recall, it.mrr, it.recallByKind)) }
        val hybrid = summaries.getValue(Mode.HYBRID)
        val lexical = summaries.getValue(Mode.LEXICAL)

        assertTrue(hybrid.recall >= 0.80, "hybrid recall@5 ${hybrid.recall}")
        assertEquals(1.0, hybrid.recallByKind.getValue("keyword"))
        assertTrue(hybrid.recall >= lexical.recall)
        assertTrue(hybrid.recallByKind.getValue("paraphrase") > lexical.recallByKind.getValue("paraphrase"))
    }

    private companion object {
        const val OVERLAP_FLOOR = 0.85 // measured 0.89 with mxbai-embed-large (0.84 nomic)
    }
}
