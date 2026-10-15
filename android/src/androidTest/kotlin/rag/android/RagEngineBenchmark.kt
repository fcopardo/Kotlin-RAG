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
 * holding `book.pdf` and each model's files as `<repository name>/onnx/model_quantized.onnx` and
 * `<repository name>/vocab.txt`. `ragBenchTier=small|large` forces a tier. Results go to logcat (tag RagBench).
 */
@RunWith(AndroidJUnit4::class)
class RagEngineBenchmark {

    @Test
    fun measure() {
        val benchDir = InstrumentationRegistry.getArguments().getString("ragBenchDir")
        assumeTrue("ragBenchDir not set", benchDir != null)
        val dir = File(benchDir!!)
        // The tier's model (SPEC §15.9; default: this phone's), and ONNX Runtime's CPU arena on or off.
        val tier = InstrumentationRegistry.getArguments().getString("ragBenchTier")?.let { DeviceTier.valueOf(it.uppercase()) }
        val arena = InstrumentationRegistry.getArguments().getString("ragBenchArena")?.toBoolean()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val questionsFile = File(context.cacheDir, "compose-internals.jsonl")
        instrumentation.context.assets.open("eval/compose-internals.jsonl").use { i -> questionsFile.outputStream().use { i.copyTo(it) } }
        val questions = Evaluation.load(questionsFile)
        val db = File(context.cacheDir, "bench.db").apply { delete() }

        report("device", "${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}, ${Runtime.getRuntime().availableProcessors()} cores")
        val build = RagModels.forDevice(context, tier)
        report("config", "model ${build.storedName} (tier ${tier ?: RagModels.tier(RagModels.memory(context))}), cpu arena ${arena ?: "tier default"}")
        report("rss.baseline", memory())

        // 1. Read and split the book with no model loaded: the PDF parser's own peak.
        var t = SystemClock.elapsedRealtime()
        val bookId = runBlocking { RagEngine(context, db).use { it.index(File(dir, "book.pdf")).bookId } }
        report("index.structure", "${fmt((SystemClock.elapsedRealtime() - t) / 1000.0)} s, no model loaded")
        report("rss.afterStructure", memory())
        resetPeak()

        // 2. Load the model and embed the stored paragraphs.
        t = SystemClock.elapsedRealtime()
        val chosen = tier ?: RagModels.tier(RagModels.memory(context))
        // The tier's own settings (RagModels.sessionOptions), unless ragBenchArena overrides them.
        val options = if (arena == null) RagModels.sessionOptions(chosen) else OrtSession.SessionOptions().apply {
            setCPUArenaAllocator(arena)
            setMemoryPatternOptimization(arena)
        }
        val embedder = build.open(File(dir, build.directoryName), options)
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

    /**
     * Where reading a PDF spends memory (SPEC §15.9 step 4): PdfBox text extraction alone, the whole
     * pipeline, then the database writes, each with a 20 ms sampler of RSS and Java heap.
     */
    @Test
    fun parsePeaks() {
        val benchDir = InstrumentationRegistry.getArguments().getString("ragBenchDir")
        assumeTrue("ragBenchDir not set", benchDir != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context)
        val book = File(benchDir!!, "book.pdf")
        report("parse.baseline", memory())

        fun <T> sampled(name: String, block: () -> T): T {
            System.gc()
            resetPeak()
            var maxHeap = 0L
            var running = true
            val sampler = Thread {
                while (running) {
                    val rt = Runtime.getRuntime()
                    maxHeap = maxOf(maxHeap, rt.totalMemory() - rt.freeMemory())
                    Thread.sleep(20)
                }
            }.apply { start() }
            val t = SystemClock.elapsedRealtime()
            val result = block()
            running = false
            sampler.join()
            report("parse.$name", "${SystemClock.elapsedRealtime() - t} ms, peak Java heap ${maxHeap / 1_048_576} MB, native heap now ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB; ${memory()}")
            return result
        }

        val extractor = rag.extract.PdfExtractor()
        sampled("pdfboxPages") { extractor.open(book).use { extractor.readPages(it) }.size }
        // The pipeline's stages one by one, as PdfBookSource.read runs them.
        extractor.open(book).use { document ->
            val raw = extractor.readPages(document)
            val plan = sampled("stage.detect") { rag.structure.ChapterDetector().detect(document, raw, null) }
            val assembled = sampled("stage.assemble") {
                extractor.assemble(raw, plan.starts.map { rag.structure.OutlineChapters.ownTitle(it.title) to it.pdfPage }, plan.tocPages)
            }
            val chapters = sampled("stage.chapters") { rag.structure.Chapters.compute(assembled, plan.starts) }
            sampled("stage.segment") { rag.structure.Segmenter().segment(assembled, chapters).size }
        }
        val structured = sampled("pipeline") { rag.BookReader(listOf(rag.PdfBookSource())).read(book) }
        sampled("store") {
            SqliteStore(File(context.cacheDir, "parse.db").apply { delete() }.path, BundledSqlDriver()).use {
                it.insertBook(structured, "sha", structured.extractor)
            }
        }
    }

    /**
     * Search across a many-book library (SPEC §8.4): `<ragBenchDir>/scale.db`, built on the desktop
     * by ScaleTest (RAG_SCALE=100, RAG_SCALE_DIR) with FakeEmbedder vectors. Times two 1-bit cache
     * sizes: 16 MB (the default, every book's bits in RAM) and 4 MB (most books streamed from SQLite).
     */
    @Test
    fun libraryScale() {
        val benchDir = InstrumentationRegistry.getArguments().getString("ragBenchDir")
        assumeTrue("ragBenchDir not set", benchDir != null)
        val source = File(benchDir!!, "scale.db")
        assumeTrue("no scale.db in $benchDir", source.isFile)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = File(context.cacheDir, "scale.db").apply { delete() }
        source.copyTo(db)
        val questionsFile = File(context.cacheDir, "art-of-war-se.jsonl")
        InstrumentationRegistry.getInstrumentation().context.assets.open("eval/art-of-war-se.jsonl").use { i -> questionsFile.outputStream().use { i.copyTo(it) } }
        val queries = Evaluation.load(questionsFile).map { it.query }
        val embedder = rag.embed.FakeEmbedder()
        SqliteStore(db.path, BundledSqlDriver()).use { store ->
            report("library", "${store.books().size} books, DB ${db.length() / 1_048_576} MB; ${memory()}")
            for ((name, bytes) in listOf("16mb" to Searcher.BITS_CACHE_BYTES, "4mb" to 4L * 1024 * 1024)) {
                val searcher = Searcher(store, embedder, bitsCacheBytes = bytes)
                for (mode in listOf(Mode.LEXICAL, Mode.VECTOR, Mode.HYBRID)) {
                    searcher.search("warm up", 5, mode = mode)
                    val times = queries.map { q ->
                        val t = SystemClock.elapsedRealtimeNanos()
                        searcher.search(q, 5, mode = mode)
                        (SystemClock.elapsedRealtimeNanos() - t) / 1_000_000.0
                    }.sorted()
                    report("library.$name.${mode.name.lowercase()}", "median ${fmt(times[times.size / 2])} ms, max ${fmt(times.last())} ms")
                }
                report("library.$name.cache", "${searcher.cachedBitsBytes / 1_048_576} MB of 1-bit vectors in RAM; ${memory()}")
            }
        }
        db.delete()
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
