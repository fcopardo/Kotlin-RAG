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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ONNX Runtime vs Ollama for mxbai-embed-large (SPEC §15.4, §15.8). Needs RAG_OLLAMA=1 and
 * RAG_ONNX_MODEL_DIR (models/mxbai-embed-large-v1 with vocab.txt and onnx/model{,_quantized}.onnx).
 */
class OnnxParityTest : OnnxParity(OnnxModels.MXBAI_EMBED_LARGE_INT8, "RAG_ONNX_MODEL_DIR")

/**
 * The same for nomic-embed-text v1.5, the small-phone model (SPEC §15.9). Needs RAG_OLLAMA=1 and
 * RAG_ONNX_NOMIC_DIR (models/nomic-embed-text-v1.5, same layout).
 */
class NomicOnnxParityTest : OnnxParity(OnnxModels.NOMIC_EMBED_TEXT_INT8, "RAG_ONNX_NOMIC_DIR") {

    /**
     * nomic's own recipe layer-norms before truncating; Ollama and the indexer don't (§5.6).
     * Check that keeping 512 of the 768 dimensions costs at most one question.
     */
    @Test
    fun `truncating nomic to 512 dimensions keeps recall`() {
        val full = SqliteStore(tmp.resolve("nomic768.db").path).also { Indexer(it).index(epub, embedder = ollama, embedDim = 768) }
        val cut = SqliteStore(tmp.resolve("nomic512.db").path).also { Indexer(it).index(epub, embedder = ollama, embedDim = 512) }
        full.use { f ->
            cut.use { c ->
                for (mode in listOf(Mode.VECTOR, Mode.HYBRID)) {
                    val r768 = recall(f, ollama, mode)
                    val r512 = recall(c, ollama, mode)
                    println("nomic $mode recall@5: 768d %.2f, 512d %.2f".format(r768, r512))
                    assertTrue(r512 >= r768 - 0.05 - 1e-9, "$mode: 512d $r512 vs 768d $r768")
                }
            }
        }
    }
}

@Tag("ollama")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class OnnxParity(
    /** The compact build phones use (SPEC §15.9); its stored name must agree with its parity. */
    private val build: OnnxModel,
    private val dirVariable: String,
) {
    private val config = build.config
    private val pooling = build.pooling
    private val compactFile = build.modelFile
    /** A build that passed parity stores the Ollama name and shares vectors with Ollama-built indexes. */
    private val sharesVectors = build.storedName == config.name

    private lateinit var dir: File
    private lateinit var tokenizer: WordPieceTokenizer
    private lateinit var fp32: OnnxEmbedder
    private lateinit var int8: OnnxEmbedder
    protected lateinit var tmp: File
    protected val ollama = OllamaEmbedder(config)
    protected val epub = File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")
    private val evalSet = File("src/test/resources/eval/art-of-war-se.jsonl")

    @BeforeAll
    fun open() {
        dir = System.getenv(dirVariable)?.let(::File) ?: File("missing")
        assumeTrue(System.getenv("RAG_OLLAMA") == "1" && dir.resolve("onnx/model.onnx").isFile, "needs RAG_OLLAMA=1 and $dirVariable")
        tokenizer = WordPieceTokenizer.fromVocabFile(dir.resolve("vocab.txt"))
        // Named like Ollama's model: that's what lets them search each other's indexes, if parity holds.
        fp32 = OnnxEmbedder(dir.resolve("onnx/model.onnx"), tokenizer, config, config.name, pooling)
        int8 = OnnxEmbedder(dir.resolve(compactFile), tokenizer, config, build.storedName, pooling)
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

        for ((name, e) in listOf("fp32" to fp32, compactFile.substringAfterLast('/') to int8)) {
            val d = cosines(e.embedDocuments(docs), refDocs)
            val q = cosines(queries.map(e::embedQuery), refQueries)
            println("${config.name} onnx $name vs ollama: documents avg %.4f min %.4f; queries avg %.4f min %.4f".format(d.average(), d.min(), q.average(), q.min()))
            val passes = d.average() >= 0.99 && q.average() >= 0.99
            if (e === fp32) assertTrue(d.average() >= 0.999 && q.average() >= 0.999, "fp32 parity")
            // The registry must match the measurement: shared name only with parity (SPEC §15.4).
            else assertEquals(sharesVectors, passes, "$name: stored as '${build.storedName}' but parity ${if (passes) "holds" else "fails"}")
        }
    }

    protected fun recall(store: SqliteStore, queryEmbedder: Embedder, mode: Mode): Double =
        Evaluation.summarize(Evaluation.run(Searcher(store, queryEmbedder), Evaluation.load(evalSet), listOf(mode)), k = 5).single().recall

    @Test
    fun `an Ollama-built index answers ONNX queries, and an ONNX-built index answers Ollama queries`() {
        val byOllama = SqliteStore(tmp.resolve("ollama.db").path).also { Indexer(it).index(epub, embedder = ollama) }
        val byInt8 = SqliteStore(tmp.resolve("int8.db").path).also { Indexer(it).index(epub, embedder = int8) }
        byOllama.use { o ->
            byInt8.use { p ->
                for (mode in listOf(Mode.VECTOR, Mode.HYBRID)) {
                    val base = recall(o, ollama, mode)
                    // Without parity the compact build is its own model: judged on its own index.
                    val results = if (sharesVectors) mapOf(
                        "ollama index, fp32 queries" to recall(o, fp32, mode),
                        "ollama index, int8 queries" to recall(o, int8, mode),
                        "int8 index, ollama queries" to recall(p, ollama, mode),
                    ) else mapOf(
                        "ollama index, fp32 queries" to recall(o, fp32, mode),
                        "int8 index, int8 queries" to recall(p, int8, mode),
                    )
                    println("${config.name} $mode recall@5: ollama/ollama %.2f, ".format(base) + results.entries.joinToString { "%s %.2f".format(it.key, it.value) })
                    results.forEach { (what, r) -> assertTrue(kotlin.math.abs(r - base) <= 0.05 + 1e-9, "$mode $what: $r vs $base") }
                }
            }
        }
    }
}
