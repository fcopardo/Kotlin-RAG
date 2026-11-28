package rag.android

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rag.embed.Embedder
import rag.embed.OnnxModel
import rag.embed.OnnxModels
import rag.search.Mode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two loading scenarios of SPEC §19.2 with the real phone models: a device that keeps two models loaded, and
 * one forced to load one at a time. Opt-in: needs `-Pandroid.testInstrumentationRunnerArguments.ragModelsDir=<dir>`
 * holding each model's files as `<repository name>/onnx/model_quantized.onnx` and `<repository name>/vocab.txt`
 * (as `ragBenchDir` does). Load times and memory go to logcat (tag RagSwap).
 */
@RunWith(AndroidJUnit4::class)
class ModelSwapDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mxbai = OnnxModels.MXBAI_EMBED_LARGE_INT8
    private val nomic = OnnxModels.NOMIC_EMBED_TEXT_INT8
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        val dir = InstrumentationRegistry.getArguments().getString("ragModelsDir")
        assumeTrue("ragModelsDir not set", dir != null)
        modelsDir = File(dir!!)
        listOf(mxbai, nomic).forEach { m ->
            assumeTrue("${m.directoryName} files missing in $modelsDir", File(modelsDir, "${m.directoryName}/${m.modelFile}").isFile)
        }
    }

    /** A provider over [modelsDir] that counts opens and closes and logs load time and memory. */
    private inner class Counting {
        var opens = 0
        var openNow = 0
        var peakOpen = 0
        val provider = CachingModelProvider(RagModels.memory(context)) { model, tier, policy -> open(model, tier, policy) }

        private fun open(model: OnnxModel, tier: DeviceTier, policy: ModelPolicy): Embedder {
            val t = SystemClock.elapsedRealtime()
            val embedder = model.open(File(modelsDir, model.directoryName), policy.sessionOptions(tier))
            embedder.embedQuery("warm up") // the session loads on first use
            opens++
            openNow++
            peakOpen = maxOf(peakOpen, openNow)
            log("load ${model.storedName}: ${SystemClock.elapsedRealtime() - t} ms, PSS ${Debug.getPss() / 1024} MB")
            return object : Embedder by embedder, AutoCloseable {
                override fun close() {
                    embedder.close()
                    openNow--
                    log("unload ${model.storedName}, PSS ${Debug.getPss() / 1024} MB")
                }
            }
        }
    }

    private fun log(message: String) = Log.i("RagSwap", message)

    @Test
    fun twoModelsStayLoadedTogetherOnALargeTierDevice() {
        val policy = DefaultModelPolicy()
        val memory = RagModels.memory(context)
        assumeTrue("needs a large-tier device", policy.tier(memory) == DeviceTier.LARGE)
        assertEquals(2, policy.maxLoadedModels(memory))
        val c = Counting().apply { provider.bind(policy) }
        log("two together: start, PSS ${Debug.getPss() / 1024} MB")
        val a = c.provider.embedderFor(mxbai)!!
        val b = c.provider.embedderFor(nomic)!!
        repeat(3) {
            assertSame(a, c.provider.embedderFor(mxbai))
            assertSame(b, c.provider.embedderFor(nomic))
        }
        assertEquals(1024, a.embedQuery("coroutines").size)
        assertEquals(768, b.embedQuery("corrutinas").size)
        assertEquals(2, c.opens, "each model loaded once")
        assertEquals(2, c.peakOpen)
        assertEquals(listOf(mxbai.storedName, nomic.storedName), c.provider.loaded())
        c.provider.close()
        assertEquals(0, c.openNow)
    }

    @Test
    fun oneModelAtATimeSwapsAndNeverHoldsTwo() {
        val policy = ForcedModelPolicy(maxLoadedModels = 1)
        val c = Counting().apply { provider.bind(policy) }
        log("one at a time: start, PSS ${Debug.getPss() / 1024} MB")
        repeat(3) {
            assertEquals(1024, c.provider.embedderFor(mxbai)!!.embedQuery("coroutines").size)
            assertEquals(768, c.provider.embedderFor(nomic)!!.embedQuery("corrutinas").size)
            assertTrue(c.provider.loaded().size == 1)
        }
        assertEquals(6, c.opens, "every switch reloads")
        assertEquals(1, c.peakOpen, "never two models open")
        c.provider.close()
        assertEquals(0, c.openNow)
    }

    @Test
    fun theEngineSearchesWithTheProvidersModelUnderEitherPolicy() = runBlocking {
        val db = File(context.cacheDir, "rag-swap-test/code.db").apply { parentFile!!.apply { deleteRecursively(); mkdirs() } }
        InstrumentationRegistry.getInstrumentation().context.assets.open("code.db").use { i -> db.outputStream().use { i.copyTo(it) } }
        for (policy in listOf(DefaultModelPolicy(), ForcedModelPolicy(maxLoadedModels = 1), ForcedModelPolicy(tier = DeviceTier.SMALL))) {
            val c = Counting()
            RagEngine(context, db, policy, c.provider).use { engine ->
                // code.db has no vectors: the engine loads the policy's model for the query, then searches by keywords.
                val hits = engine.search("normalize source indentation tabs", mode = Mode.HYBRID)
                assertTrue(hits.first().paragraph.pageStartLabel.endsWith(".kt"))
                assertEquals(listOf(policy.model(policy.tier(RagModels.memory(context))).storedName), c.provider.loaded())
            }
            c.provider.close()
        }
    }
}
