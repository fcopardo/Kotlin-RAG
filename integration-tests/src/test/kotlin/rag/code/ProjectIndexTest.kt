package rag.code

import org.junit.jupiter.api.io.TempDir
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.store.SqliteStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Plan step 4: indexing a project folder end to end, and re-index detection by fingerprint. */
class ProjectIndexTest {
    private fun put(root: File, path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun project(dir: File): File {
        val root = File(dir, "demo")
        put(root, "core/src/main/kotlin/rag/search/Searcher.kt", "package rag.search\n\nclass Searcher {\n    fun search(q: String): List<String> {\n        val words = q.split(' ')\n\n        return words\n    }\n}\n")
        put(root, "core/src/main/kotlin/rag/Main.kt", "fun main() {\n    println(\"hi\")\n}\n")
        put(root, "tools/gen.py", "def gen():\n    return 1\n")
        put(root, "build/out.kt", "x")
        put(root, ".gitignore", "*.tmp\n")
        put(root, "notes.tmp", "scratch")
        return root
    }

    @Test
    fun `a project indexes as files, classes and functions, with its files recorded`(@TempDir dir: File) =
        SqliteStore(File(dir, "code.db").path).use { store ->
            val root = project(dir)
            val result = Indexer(store).index(root, embedder = FakeEmbedder())
            val book = store.book(result.bookId)!!
            assertEquals("demo", book.title)
            assertEquals("code", book.kind)
            assertEquals(listOf("core/src/main/kotlin/rag/Main.kt", "core/src/main/kotlin/rag/search/Searcher.kt", "tools/gen.py"),
                store.projectFiles(result.bookId).map { it.path })
            val text = store.bookText(result.bookId)
            for (f in store.projectFiles(result.bookId)) {
                assertTrue(text.substring(f.charStart, f.charEnd + 1).isNotBlank(), f.path)
            }
            assertEquals(ProjectReader().read(root).fingerprint, book.fileSha256)
        }

    @Test
    fun `an unchanged project is not indexed twice, a changed one is new`(@TempDir dir: File) =
        SqliteStore(File(dir, "code.db").path).use { store ->
            val root = project(dir)
            val first = Indexer(store).index(root)
            val again = Indexer(store).index(root)
            assertTrue(again.skipped)
            assertEquals(first.bookId, again.bookId)

            put(root, "scratch.tmp", "ignored files don't count")
            assertTrue(Indexer(store).index(root).skipped)

            put(root, "tools/gen.py", "def gen():\n    return 2\n")
            val changed = Indexer(store).index(root)
            assertTrue(!changed.skipped)
            assertNotEquals(first.bookId, changed.bookId)
        }

    @Test
    fun `reindex replaces the project and its file rows`(@TempDir dir: File) =
        SqliteStore(File(dir, "code.db").path).use { store ->
            val root = project(dir)
            val first = Indexer(store).index(root)
            val rebuilt = Indexer(store).index(root, reindex = true)
            assertTrue(!rebuilt.skipped)
            assertEquals(3, store.projectFiles(rebuilt.bookId).size)
            if (rebuilt.bookId != first.bookId) assertEquals(emptyList(), store.projectFiles(first.bookId), "old rows are gone")
        }

    @Test
    fun `a single source file indexes as a project of one, keyed by its bytes`(@TempDir dir: File) =
        SqliteStore(File(dir, "code.db").path).use { store ->
            val f = put(dir, "Solo.kt", "class Solo {\n    fun hi() = 1\n}\n")
            val id = Indexer(store).index(f).bookId
            assertEquals(Indexer.sha256(f), store.book(id)!!.fileSha256)
            assertEquals(listOf("Solo.kt"), store.projectFiles(id).map { it.path })
        }
}
