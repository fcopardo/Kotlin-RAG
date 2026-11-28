package rag.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rag.embed.Embedder
import rag.embed.FakeEmbedder
import rag.embed.OnnxModel
import rag.embed.OnnxModels
import rag.search.Mode
import rag.store.BundledSqlDriver
import rag.store.SqliteStore
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 10 step 7 on the device (SPEC §19.4): with an English expert and a shared model, a document is embedded with
 * the models its language needs, a one-language search uses the expert and a search across languages the shared model.
 * Fake models stand in for the real ones; `ModelSwapDeviceTest` loads real ones.
 */
@RunWith(AndroidJUnit4::class)
class LanguageRoutingDeviceTest {
    private lateinit var dir: File
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val expert = OnnxModels.MXBAI_EMBED_LARGE_INT8
    private val shared = OnnxModels.NOMIC_EMBED_TEXT_INT8

    private val english = "The library reads each book once and stores its text with the chapters, paragraphs and sentences it found. " +
        "When you ask a question, it looks for the words you typed and for passages that mean the same thing. "
    private val spanish = "La biblioteca lee cada libro una sola vez y guarda su texto con los capítulos, párrafos y frases que " +
        "encontró. Cuando haces una pregunta, busca las palabras que escribiste y también los pasajes que significan lo mismo. "

    /** Routes like a policy with an English expert and a shared model; records which models were asked for. */
    private class RoutingProvider : ModelProvider {
        val asked = mutableListOf<String>()
        private val fakes = mapOf(
            OnnxModels.MXBAI_EMBED_LARGE_INT8 to FakeEmbedder(model = "fake-expert"),
            OnnxModels.NOMIC_EMBED_TEXT_INT8 to FakeEmbedder(dim = 512, model = "fake-shared"),
        )
        override fun bind(policy: ModelPolicy) {}
        override fun embedder(): Embedder? = null
        override fun indexModels(language: String): List<OnnxModel> =
            if (language == "en" || language == "zxx" || language == "und") listOf(OnnxModels.MXBAI_EMBED_LARGE_INT8, OnnxModels.NOMIC_EMBED_TEXT_INT8)
            else listOf(OnnxModels.NOMIC_EMBED_TEXT_INT8)
        override fun searchModel(languages: Set<String>): OnnxModel? =
            if (languages.map { if (it == "zxx") "en" else it }.toSet() == setOf("en")) OnnxModels.MXBAI_EMBED_LARGE_INT8 else OnnxModels.NOMIC_EMBED_TEXT_INT8
        override fun embedderFor(model: OnnxModel): Embedder? = fakes[model].also { asked += it!!.model }
        override fun close() {}
    }

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "rag-routing-test").apply { deleteRecursively(); mkdirs() }
    }

    @Test
    fun documentsGetTheirLanguagesModelsAndSearchesPickTheRightOne() = runBlocking {
        val db = File(dir, "lib.db")
        val provider = RoutingProvider()
        RagEngine(context, db, DefaultModelPolicy(), provider).use { engine ->
            val en = engine.index(File(dir, "notes.md").apply { writeText("# Notes\n\n" + english.repeat(4)) }).bookId
            val es = engine.index(File(dir, "notas.md").apply { writeText("# Notas\n\n" + spanish.repeat(4)) }).bookId
            assertEquals(listOf("fake-expert", "fake-shared", "fake-shared"), provider.asked, "en: expert then shared; es: shared")
            SqliteStore(db.path, BundledSqlDriver()).use { store ->
                assertEquals(listOf("fake-expert", "fake-shared"), store.book(en)!!.models.map { it.model })
                assertEquals(listOf("fake-shared"), store.book(es)!!.models.map { it.model })
                assertTrue(store.book(en)!!.models.all { it.status == "complete" })
            }

            provider.asked.clear()
            val englishHits = engine.search("library question", languages = listOf("en"), mode = Mode.VECTOR)
            assertEquals(listOf("fake-expert"), provider.asked, "one language: its expert")
            assertTrue(englishHits.isNotEmpty() && englishHits.all { it.language == "en" })

            provider.asked.clear()
            val both = engine.search("library question", mode = Mode.VECTOR)
            assertEquals(listOf("fake-shared"), provider.asked, "several languages: the shared model")
            assertEquals(setOf("en", "es"), both.map { it.language }.toSet(), "the shared model reaches both")

            provider.asked.clear()
            engine.search("biblioteca", languages = listOf("es"), mode = Mode.VECTOR)
            assertEquals(listOf("fake-shared"), provider.asked, "Spanish has no expert here: the shared model")
        }
    }

    @Test
    fun resumingEmbeddingAddsWhatTheLanguageNeedsAndKeepsOtherModels() = runBlocking {
        val db = File(dir, "lib.db")
        val provider = RoutingProvider()
        RagEngine(context, db, DefaultModelPolicy(), provider).use { engine ->
            val en = engine.index(File(dir, "notes.md").apply { writeText("# Notes\n\n" + english.repeat(4)) }, embed = false).bookId
            provider.asked.clear()
            engine.embed(en)
            assertEquals(listOf("fake-expert", "fake-shared"), provider.asked)
            engine.embed(en) // nothing missing: a no-op that keeps both sets
            SqliteStore(db.path, BundledSqlDriver()).use { store ->
                assertEquals(listOf("fake-expert", "fake-shared"), store.book(en)!!.models.map { it.model })
            }
        }
    }
}
