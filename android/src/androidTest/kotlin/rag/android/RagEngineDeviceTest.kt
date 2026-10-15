package rag.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rag.embed.FakeEmbedder
import rag.search.Mode
import rag.search.Searcher
import rag.store.BundledSqlDriver
import rag.store.Goldens
import rag.store.SqliteStore
import java.io.File
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPEC §15.8 on a device: the bundled SQLite, a desktop-built index searched on the device, the
 * device indexing the same books into the same units, and cancel/resume of embedding. Inputs and
 * goldens come from `:integration-tests:androidGoldens` (built on the desktop) as test assets.
 */
@RunWith(AndroidJUnit4::class)
class RagEngineDeviceTest {

    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var goldens: Properties

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        dir = File(context.cacheDir, "rag-test").apply { deleteRecursively(); mkdirs() }
        val assets = instrumentation.context.assets
        (Goldens.BOOKS.flatMap { listOf(it, "$it.units.txt") } + listOf("desktop.db", "goldens.properties")).forEach { name ->
            assets.open(name).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        goldens = Properties().apply { File(dir, "goldens.properties").inputStream().use { load(it) } }
    }

    @Test
    fun bundledSqliteHasFts5() {
        SqliteStore(File(dir, "probe.db").path, BundledSqlDriver()).use { store ->
            val version = store.db.query("SELECT sqlite_version()", emptyList()) { it.string(1)!! }.single()
            val options = store.db.query("PRAGMA compile_options", emptyList()) { it.string(1)!! }
            assertTrue(version >= "3.50", "bundled SQLite $version")
            assertTrue("ENABLE_FTS5" in options, "FTS5 missing from $options")
        }
    }

    @Test
    fun desktopIndexSearchesTheSameOnDevice() = runBlocking {
        RagEngine(context, File(dir, "desktop.db")).use { engine ->
            Goldens.QUERIES.forEachIndexed { i, query ->
                val keys = engine.search(query, mode = Mode.LEXICAL).joinToString("|") { "${it.bookId}:${it.paragraph.unitKey}" }
                assertEquals(goldens.getProperty("search.$i"), keys, "query '$query'")
            }
        }
        // Each model the DB carries searches with its own vectors (SPEC §15.9).
        for (model in listOf(Goldens.MODEL_A, Goldens.MODEL_B)) {
            RagEngine(context, File(dir, "desktop.db"), model).use { engine ->
                Goldens.QUERIES.forEachIndexed { i, query ->
                    val keys = engine.search(query, mode = Mode.VECTOR).joinToString("|") { "${it.bookId}:${it.paragraph.unitKey}" }
                    assertEquals(goldens.getProperty("vector.${model.model}.$i"), keys, "${model.model} '$query'")
                }
            }
        }
    }

    @Test
    fun thisPhoneIsLargeTier() {
        // The S25 reports ~10.9 GiB; the tier rules themselves are unit-tested (RagModelsTest).
        val memory = RagModels.memory(context)
        assertEquals(DeviceTier.LARGE, RagModels.tier(memory), "$memory")
    }

    @Test
    fun deviceIndexMatchesDesktop() {
        val db = File(dir, "device.db")
        runBlocking {
            RagEngine(context, db).use { engine ->
                Goldens.BOOKS.forEach { name ->
                    val result = engine.index(File(dir, name))
                    assertEquals(goldens.getProperty("$name.bookId").toLong(), result.bookId, name)
                }
            }
        }
        SqliteStore(db.path, BundledSqlDriver()).use { store ->
            Goldens.BOOKS.forEach { name ->
                val bookId = goldens.getProperty("$name.bookId").toLong()
                assertEquals(goldens.getProperty("$name.textSha256"), Goldens.sha256(store.bookText(bookId)), "$name text")
                assertEquals(goldens.getProperty("$name.chapters"), Goldens.chapters(store, bookId), "$name chapters")
                assertEquals(goldens.getProperty("$name.strategy"), store.book(bookId)!!.chapterStrategy, "$name strategy")
                val expected = File(dir, "$name.units.txt").readText().split("\n")
                val actual = Goldens.unitLines(store, bookId)
                val diffs = expected.zip(actual).withIndex().filter { (_, p) -> p.first != p.second }
                if (diffs.isNotEmpty()) fail("$name: ${diffs.size} unit lines differ\n" +
                    diffs.take(20).joinToString("\n") { (i, p) -> "line ${i + 1}: desktop '${p.first}', device '${p.second}'" })
                assertEquals(expected.size, actual.size, "$name unit count")
            }
            val searcher = Searcher(store, embedder = null)
            Goldens.QUERIES.forEachIndexed { i, query ->
                assertEquals(goldens.getProperty("search.$i"), Goldens.lexicalKeys(searcher, query), "query '$query'")
            }
        }
    }

    @Test
    fun cancelledEmbeddingStaysPartialAndResumes() = runBlocking {
        val embedder = FakeEmbedder()
        RagEngine(context, File(dir, "cancel.db"), embedder).use { engine ->
            var firstTotal = 0
            val job = launch {
                engine.index(File(dir, "art-of-war-se.epub")) { _, total ->
                    firstTotal = total
                    cancel() // stops at the next batch boundary
                }
            }
            job.join()
            assertTrue(job.isCancelled)
            val book = engine.books().single()
            assertEquals("partial", book.embedStatus)

            var resumedTotal = 0
            val resumed = engine.embed(book.id) { _, total -> resumedTotal = total }
            assertEquals("complete", resumed.status)
            assertEquals("complete", engine.books().single().embedStatus)
            assertTrue(resumedTotal in 1 until firstTotal, "resume embeds only the rest: $resumedTotal of $firstTotal")
            assertTrue(engine.search("attack by stratagem", mode = Mode.VECTOR).isNotEmpty())
        }
    }
}
