package rag.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rag.embed.Embedder
import rag.embed.FakeEmbedder
import rag.search.Mode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [RagEngine] with a [ModelPolicy] and a [ModelProvider] (SPEC §19.2): the engine binds the provider to its policy
 * before anything else, asks the provider for the embedder each operation needs, and leaves the provider open.
 */
@RunWith(AndroidJUnit4::class)
class EngineProviderDeviceTest {
    private lateinit var db: File
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private class RecordingProvider(var current: Embedder?) : ModelProvider {
        val events = mutableListOf<String>()
        var bound: ModelPolicy? = null
        override fun bind(policy: ModelPolicy) {
            events += "bind"
            bound = policy
        }
        override fun embedder(): Embedder? = current.also { events += "embedder" }
        override fun close() {
            events += "close"
        }
    }

    @Before
    fun setUp() {
        val dir = File(context.cacheDir, "rag-provider-test").apply { deleteRecursively(); mkdirs() }
        db = File(dir, "code.db")
        InstrumentationRegistry.getInstrumentation().context.assets.open("code.db").use { input -> db.outputStream().use { input.copyTo(it) } }
    }

    @Test
    fun theEngineBindsItsPolicyFirstAndAsksForAnEmbedderPerOperation() = runBlocking {
        val policy = ForcedModelPolicy(maxLoadedModels = 1)
        val provider = RecordingProvider(null)
        RagEngine(context, db, policy, provider).use { engine ->
            assertEquals(listOf("bind"), provider.events)
            assertSame(policy, provider.bound)
            engine.search("normalize source indentation tabs", mode = Mode.LEXICAL)
            engine.searchByKind("normalize source indentation tabs", mode = Mode.LEXICAL)
        }
        assertEquals(listOf("bind", "embedder", "embedder"), provider.events, "the engine leaves the provider open")
    }

    @Test
    fun aSwappedEmbedderIsUsedFromTheNextSearch() = runBlocking {
        val first = FakeEmbedder(model = "fake-a")
        val second = FakeEmbedder(model = "fake-b")
        val provider = RecordingProvider(first)
        RagEngine(context, db, ModelPolicies.current, provider).use { engine ->
            // Neither model embedded this index: the engine falls back to keywords and still answers.
            assertTrue(engine.search("normalize source indentation tabs").isNotEmpty())
            provider.current = second
            assertTrue(engine.search("normalize source indentation tabs").isNotEmpty())
            provider.current = null
            assertTrue(engine.search("normalize source indentation tabs").isNotEmpty())
        }
    }

    @Test
    fun theEmbedderConstructorStillWorks() = runBlocking {
        RagEngine(context, db).use { engine ->
            val hits = engine.search("normalize source indentation tabs", mode = Mode.LEXICAL)
            assertTrue(hits.first().paragraph.pageStartLabel.endsWith(".kt"))
        }
    }
}
