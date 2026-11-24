package rag.android

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
 * Code on the device (Phase 9 app update, step 2): the frozen `core` snapshot, indexed on the device as a
 * project, gives the same text, sections, files and units as on the desktop; a desktop-built code index
 * is searched by kind on the device. Goldens come from `:integration-tests:androidGoldens`.
 */
@RunWith(AndroidJUnit4::class)
class CodeOnDeviceTest {
    private lateinit var dir: File
    private lateinit var goldens: Properties
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun copyTree(assets: AssetManager, path: String, to: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            assets.open(path).use { input -> to.apply { parentFile!!.mkdirs() }.outputStream().use { input.copyTo(it) } }
        } else {
            children.forEach { copyTree(assets, "$path/$it", File(to, it)) }
        }
    }

    @Before
    fun setUp() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        dir = File(context.cacheDir, "rag-code-test").apply { deleteRecursively(); mkdirs() }
        listOf("code.db", "code-goldens.properties", "core-snapshot.units.txt").forEach { name ->
            assets.open(name).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        copyTree(assets, "code/core-snapshot", File(dir, "core-snapshot"))
        goldens = Properties().apply { File(dir, "code-goldens.properties").inputStream().use { load(it) } }
    }

    @Test
    fun deviceIndexesTheProjectLikeTheDesktop() {
        val db = File(dir, "device-code.db")
        runBlocking {
            RagEngine(context, db).use { engine ->
                val result = engine.index(File(dir, "core-snapshot"), embed = false)
                assertEquals(goldens.getProperty("bookId").toLong(), result.bookId)
            }
        }
        SqliteStore(db.path, BundledSqlDriver()).use { store ->
            val bookId = goldens.getProperty("bookId").toLong()
            assertEquals("code", store.book(bookId)!!.kind)
            assertEquals(goldens.getProperty("textSha256"), Goldens.sha256(store.bookText(bookId)), "text")
            assertEquals(goldens.getProperty("chapters"), Goldens.chapters(store, bookId), "sections")
            assertEquals(goldens.getProperty("strategy"), store.book(bookId)!!.chapterStrategy)
            assertEquals(goldens.getProperty("files"), store.projectFiles(bookId).joinToString("|") { "${it.path}:${it.sha256}" }, "files")
            val expected = File(dir, "core-snapshot.units.txt").readText().split("\n")
            val actual = Goldens.unitLines(store, bookId)
            val diffs = expected.zip(actual).withIndex().filter { (_, p) -> p.first != p.second }
            if (diffs.isNotEmpty()) fail("${diffs.size} unit lines differ\n" +
                diffs.take(20).joinToString("\n") { (i, p) -> "line ${i + 1}: desktop '${p.first}', device '${p.second}'" })
            assertEquals(expected.size, actual.size, "unit count")
            assertEquals(goldens.getProperty("search"), Goldens.lexicalKeys(Searcher(store, embedder = null), "normalize source indentation tabs"))
        }
    }

    @Test
    fun aDesktopCodeIndexIsSearchedByKindOnTheDevice() = runBlocking {
        RagEngine(context, File(dir, "code.db")).use { engine ->
            val byKind = engine.searchByKind("normalize source indentation tabs", mode = Mode.LEXICAL)
            assertEquals(listOf("code"), byKind.keys.toList())
            val top = byKind.getValue("code").first()
            assertEquals("file", top.pageKind)
            assertTrue(top.paragraph.pageStartLabel.endsWith(".kt"), top.paragraph.pageStartLabel)
        }
    }
}
