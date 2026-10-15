package rag.android

import ai.onnxruntime.OrtSession
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rag.embed.EmbeddingModel
import rag.embed.OnnxEmbedder
import rag.embed.WordPieceTokenizer
import rag.search.Evaluation
import rag.search.Mode
import rag.search.Searcher
import rag.store.BundledSqlDriver
import rag.store.SqliteStore
import java.io.File

/**
 * SPEC §15.6 measurements on the device: model load, query embedding, indexing a real book with
 * on-device embeddings, peak memory, search latency and recall. Opt-in, like the desktop's
 * real-book tests: needs `-Pandroid.testInstrumentationRunnerArguments.ragBenchDir=<dir on device>`
 * holding `model_quantized.onnx`, `vocab.txt` and `book.pdf`. Results go to logcat (tag RagBench).
 */
@RunWith(AndroidJUnit4::class)
class RagEngineBenchmark {

    @Test
    fun measure() {
        val benchDir = InstrumentationRegistry.getArguments().getString("ragBenchDir")
        assumeTrue("ragBenchDir not set", benchDir != null)
        val dir = File(benchDir!!)
        // Memory knobs (SPEC §15.6): ONNX batch size, and ONNX Runtime's CPU arena on or off.
        val batch = InstrumentationRegistry.getArguments().getString("ragBenchBatch")?.toInt() ?: 8
        val arena = InstrumentationRegistry.getArguments().getString("ragBenchArena")?.toBoolean() ?: true
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val questionsFile = File(context.cacheDir, "compose-internals.jsonl")
        instrumentation.context.assets.open("eval/compose-internals.jsonl").use { i -> questionsFile.outputStream().use { i.copyTo(it) } }
        val questions = Evaluation.load(questionsFile)
        val db = File(context.cacheDir, "bench.db").apply { delete() }

        report("device", "${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, ${Runtime.getRuntime().availableProcessors()} cores")
        report("config", "onnx batch $batch, cpu arena $arena")
        report("rss.baseline", memory())

        // 1. Read and split the book with no model loaded: the PDF parser's own peak.
        var t = SystemClock.elapsedRealtime()
        val bookId = runBlocking { RagEngine(context, db).use { it.index(File(dir, "book.pdf")).bookId } }
        report("index.structure", "${fmt((SystemClock.elapsedRealtime() - t) / 1000.0)} s, no model loaded")
        report("rss.afterStructure", memory())
        resetPeak()

        // 2. Load the model and embed the stored paragraphs.
        t = SystemClock.elapsedRealtime()
        val embedder = OnnxEmbedder(
            File(dir, "model_quantized.onnx"),
            WordPieceTokenizer.fromVocabFile(File(dir, "vocab.txt")),
            EmbeddingModel.MXBAI_EMBED_LARGE,
            model = EmbeddingModel.MXBAI_EMBED_LARGE.name,
            batchSize = batch,
            sessionOptions = OrtSession.SessionOptions().apply {
                setCPUArenaAllocator(arena)
                setMemoryPatternOptimization(arena)
            },
        )
        embedder.dim // loads the session and runs one probe
        report("model.load", "${SystemClock.elapsedRealtime() - t} ms")
        report("rss.modelLoaded", memory())

        repeat(3) { embedder.embedQuery("warm up") }
        val queryTimes = questions.map { q ->
            val s = SystemClock.elapsedRealtimeNanos()
            embedder.embedQuery(q.query)
            (SystemClock.elapsedRealtimeNanos() - s) / 1_000_000.0
        }.sorted()
        report("query.embed", "median ${fmt(queryTimes[queryTimes.size / 2])} ms, p90 ${fmt(queryTimes[queryTimes.size * 9 / 10])} ms, max ${fmt(queryTimes.last())} ms (${queryTimes.size} questions)")

        var peakPss = 0L
        t = SystemClock.elapsedRealtime()
        val result = runBlocking {
            RagEngine(context, db, embedder).use { engine ->
                engine.embed(bookId) { done, total ->
                    peakPss = maxOf(peakPss, Debug.getPss())
                    if (done % 320 == 0 || done == total) Log.i(TAG, "embedded $done / $total")
                }
            }
        }
        report("index.embed", "${fmt((SystemClock.elapsedRealtime() - t) / 1000.0)} s (${result.embedded} texts embedded, ${result.copied} copied)")
        report("index.peakPss", "${peakPss / 1024} MB sampled per batch")
        report("rss.afterEmbed", memory())

        SqliteStore(db.path, BundledSqlDriver()).use { store ->
            val searcher = Searcher(store, embedder, warn = { Log.w(TAG, it) })
            searcher.search("warm up", 5)
            for (mode in listOf(Mode.LEXICAL, Mode.HYBRID)) {
                val times = questions.map { q ->
                    val s = SystemClock.elapsedRealtimeNanos()
                    searcher.search(q.query, 5, mode = mode)
                    (SystemClock.elapsedRealtimeNanos() - s) / 1_000_000.0
                }.sorted()
                report("search.${mode.name.lowercase()}", "median ${fmt(times[times.size / 2])} ms, max ${fmt(times.last())} ms")
            }
            Evaluation.summarize(Evaluation.run(searcher, questions, listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)), 5).forEach {
                report("recall@5.${it.mode.name.lowercase()}", "${fmt(it.recall)} MRR ${fmt(it.mrr)} ${it.recallByKind}")
            }
        }
        report("db.size", "${db.length() / 1024} KB")
        report("rss.end", memory())
        embedder.close()
    }

    /** Current and peak resident memory of this process (test app + library), from /proc. */
    private fun memory(): String {
        val status = File("/proc/self/status").readLines().associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        return "VmRSS ${status["VmRSS"]}, VmHWM (peak) ${status["VmHWM"]}, Java heap ${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576} MB"
    }

    /** Resets VmHWM so the next phase's peak is its own; not every kernel lets an app do this. */
    private fun resetPeak() {
        val ok = runCatching { File("/proc/self/clear_refs").writeText("5") }.isSuccess
        report("rss.peakReset", if (ok) "yes: ${memory()}" else "not allowed (later peaks include earlier phases)")
    }

    private fun report(key: String, value: String) = Log.i(TAG, "$key: $value")

    private fun fmt(v: Double) = "%.2f".format(v)

    private companion object {
        const val TAG = "RagBench"
    }
}
