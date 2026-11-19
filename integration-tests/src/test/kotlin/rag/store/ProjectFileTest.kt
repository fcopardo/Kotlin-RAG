package rag.store

import org.junit.jupiter.api.io.TempDir
import rag.SampleBook
import rag.embed.FakeEmbedder
import rag.index.Indexer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ProjectFileTest {

    private fun withBook(dir: File, block: (SqliteStore, Long) -> Unit) = SqliteStore(File(dir, "test.db").path).use { store ->
        block(store, Indexer(store).index(SampleBook.write(File(dir, "sample.pdf")), embedder = FakeEmbedder()).bookId)
    }

    @Test
    fun `project files read back in path order`(@TempDir dir: File) = withBook(dir) { store, bookId ->
        val b = ProjectFile("core/src/main/kotlin/rag/search/Searcher.kt", "sha-b", 101, 250)
        val a = ProjectFile("cli/src/main/kotlin/rag/Main.kt", "sha-a", 0, 99)
        store.insertProjectFiles(bookId, listOf(b, a))

        assertEquals(listOf(a, b), store.projectFiles(bookId))
    }

    @Test
    fun `a path recorded twice for a book is an error`(@TempDir dir: File) = withBook(dir) { store, bookId ->
        store.insertProjectFiles(bookId, listOf(ProjectFile("a/B.kt", "sha1", 0, 10)))

        assertFails { store.insertProjectFiles(bookId, listOf(ProjectFile("a/B.kt", "sha2", 11, 20))) }
        assertEquals(listOf(ProjectFile("a/B.kt", "sha1", 0, 10)), store.projectFiles(bookId))
    }

    @Test
    fun `deleting a book removes its project files`(@TempDir dir: File) = withBook(dir) { store, bookId ->
        store.insertProjectFiles(bookId, listOf(ProjectFile("test.kt", "sha1", 0, 100)))
        assertEquals(1, store.projectFiles(bookId).size)

        store.deleteBook(bookId)
        assertEquals(emptyList(), store.projectFiles(bookId))
    }
}
