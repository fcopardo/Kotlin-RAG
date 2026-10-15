package rag.embed

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import rag.BookReader
import rag.index.Indexer
import rag.search.Evaluation
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ONNX Runtime vs Ollama for mxbai-embed-large (SPEC §15.4, §15.8). Needs RAG_OLLAMA=1 and
 * RAG_ONNX_MODEL_DIR (models/mxbai-embed-large-v1 with vocab.txt and onnx/model{,_quantized}.onnx).
 */
@Tag("ollama")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxParityTest {

    private lateinit var dir: File
    private lateinit var tokenizer: WordPieceTokenizer
    private lateinit var fp32: OnnxEmbedder
    private lateinit var int8: OnnxEmbedder
    private lateinit var tmp: File
    private val ollama = OllamaEmbedder(EmbeddingModel.MXBAI_EMBED_LARGE)
    private val epub = File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")
    private val evalSet = File("src/test/resources/eval/art-of-war-se.jsonl")

    @BeforeAll
    fun open() {
        dir = System.getenv("RAG_ONNX_MODEL_DIR")?.let(::File) ?: File("missing")
        assumeTrue(System.getenv("RAG_OLLAMA") == "1" && dir.resolve("onnx/model.onnx").isFile, "needs RAG_OLLAMA=1 and RAG_ONNX_MODEL_DIR")
        tokenizer = WordPieceTokenizer.fromVocabFile(dir.resolve("vocab.txt"))
        // Named like Ollama's model: that's what lets them search each other's indexes, if parity holds.
        fp32 = OnnxEmbedder(dir.resolve("onnx/model.onnx"), tokenizer, EmbeddingModel.MXBAI_EMBED_LARGE, "mxbai-embed-large")
        int8 = OnnxEmbedder(dir.resolve("onnx/model_quantized.onnx"), tokenizer, EmbeddingModel.MXBAI_EMBED_LARGE, "mxbai-embed-large")
        tmp = Files.createTempDirectory("rag-onnx").toFile()
    }

    @AfterAll
    fun close() {
        if (::fp32.isInitialized) fp32.close()
        if (::int8.isInitialized) int8.close()
        if (::tmp.isInitialized) tmp.deleteRecursively()
    }

    /** 50 paragraphs as the indexer embeds them: chapter title + text, from the 13 main chapters. */
    private fun paragraphs(): List<String> {
        val b = BookReader().read(epub)
        return b.segments.filter { it.chapter.title.startsWith("The Art of War › ") }
            .flatMap { s -> s.paragraphs.map { s.chapter.title + "\n" + b.text(it.start, it.end) } }
            .filter { it.length > 120 }.take(50)
    }

    private fun cosines(onnx: List<FloatArray>, ref: List<FloatArray>) = onnx.indices.map { Vectors.dot(onnx[it], ref[it]).toDouble() }

    @Test
    fun `documents and queries match Ollama`() {
        val docs = paragraphs()
        val queries = Evaluation.load(evalSet).map { it.query }
        val refDocs = ollama.embedDocuments(docs)
        val refQueries = queries.map(ollama::embedQuery)

        for ((name, e, avgFloor) in listOf(Triple("fp32", fp32, 0.999), Triple("int8", int8, 0.99))) {
            val d = cosines(e.embedDocuments(docs), refDocs)
            val q = cosines(queries.map(e::embedQuery), refQueries)
            println("onnx $name vs ollama: documents avg %.4f min %.4f; queries avg %.4f min %.4f".format(d.average(), d.min(), q.average(), q.min()))
            assertTrue(d.average() >= avgFloor && q.average() >= avgFloor, "$name parity")
        }
    }

    private fun recall(store: SqliteStore, queryEmbedder: Embedder, mode: Mode): Double =
        Evaluation.summarize(Evaluation.run(Searcher(store, queryEmbedder), Evaluation.load(evalSet), listOf(mode)), k = 5).single().recall

    @Test
    fun `an Ollama-built index answers ONNX queries, and an ONNX-built index answers Ollama queries`() {
        val byOllama = SqliteStore(tmp.resolve("ollama.db").path).also { Indexer(it).index(epub, embedder = ollama) }
        val byInt8 = SqliteStore(tmp.resolve("int8.db").path).also { Indexer(it).index(epub, embedder = int8) }
        byOllama.use { o ->
            byInt8.use { p ->
                for (mode in listOf(Mode.VECTOR, Mode.HYBRID)) {
                    val base = recall(o, ollama, mode)
                    val results = mapOf(
                        "ollama index, fp32 queries" to recall(o, fp32, mode),
                        "ollama index, int8 queries" to recall(o, int8, mode),
                        "int8 index, ollama queries" to recall(p, ollama, mode),
                    )
                    println("$mode recall@5: ollama/ollama %.2f, ".format(base) + results.entries.joinToString { "%s %.2f".format(it.key, it.value) })
                    results.forEach { (what, r) -> assertTrue(kotlin.math.abs(r - base) <= 0.05 + 1e-9, "$mode $what: $r vs $base") }
                }
            }
        }
    }
}
