package rag.index

import org.junit.jupiter.api.io.TempDir
import rag.RagException
import rag.embed.Embedder
import rag.embed.FakeEmbedder
import rag.store.SqliteStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC §18.5 step 5, part 5a: indexing a folder of documents, one document per file. */
class FolderIndexTest {
    @TempDir
    lateinit var tmp: File

    private fun put(path: String, text: String) = File(tmp, "docs/$path").apply { parentFile.mkdirs(); writeText(text) }

    private fun folder(): File {
        put("notes.md", "# Notes\n\nFirst point.\n\n## More\n\nSecond point.\n")
        put("plain.txt", "Just some text about gardens.\n")
        put("sub/page.html", "<!doctype html><html><body><h1>Title</h1><p>Body text here.</p></body></html>")
        put("empty.txt", "")              // refused by content sniffing: not a document
        put("blank.txt", "   \n\n")       // a text file the reader refuses: failed
        File(tmp, "docs/img.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1, 2, 3))
        put("bad.pdf", "%PDF-1.4 this is not really a PDF")
        put(".hidden/secret.md", "# Secret\n\nHidden.\n")
        put(".DS_Store", "junk")
        return File(tmp, "docs")
    }

    private fun show(r: List<FolderFile>) = r.map { "${it.path} ${it.outcome}" }

    @Test
    fun `each document is indexed once, the rest is reported`() = SqliteStore(File(tmp, "docs.db").path).use { store ->
        val dir = folder()
        val seen = mutableListOf<FolderFile>()
        val r = Indexer(store).indexFolder(dir, onFile = { seen += it })
        assertEquals(
            listOf(
                "bad.pdf FAILED", "blank.txt FAILED", "empty.txt NOT_A_DOCUMENT", "img.png NOT_A_DOCUMENT",
                "notes.md INDEXED", "plain.txt INDEXED", "sub/page.html INDEXED",
            ),
            show(r),
        )
        assertEquals(r, seen, "reported as it goes, in order")
        assertEquals(3, store.books().size)
        assertEquals(r.filter { it.outcome == FolderOutcome.INDEXED }.map { it.bookId }.toSet(), store.books().map { it.id }.toSet())
        assertTrue(r.filter { it.outcome != FolderOutcome.INDEXED }.all { it.bookId == null && !it.detail.isNullOrBlank() }, "$r")
        assertTrue(r.first { it.path == "blank.txt" }.detail!!.contains("Empty document"), "${r.first { it.path == "blank.txt" }}")
        assertTrue(r.first { it.path == "empty.txt" }.detail!!.contains("Unsupported file"), "${r.first { it.path == "empty.txt" }}")
    }

    @Test
    fun `a second run indexes nothing again, a changed file is new`() = SqliteStore(File(tmp, "docs.db").path).use { store ->
        val dir = folder()
        val first = Indexer(store).indexFolder(dir)
        val again = Indexer(store).indexFolder(dir)
        assertEquals(
            listOf(
                "bad.pdf FAILED", "blank.txt FAILED", "empty.txt NOT_A_DOCUMENT", "img.png NOT_A_DOCUMENT",
                "notes.md ALREADY_INDEXED", "plain.txt ALREADY_INDEXED", "sub/page.html ALREADY_INDEXED",
            ),
            show(again),
        )
        assertEquals(first.map { it.bookId }, again.map { it.bookId })
        put("plain.txt", "Now about deserts.\n")
        val changed = Indexer(store).indexFolder(dir).first { it.path == "plain.txt" }
        assertEquals(FolderOutcome.INDEXED, changed.outcome)
        assertNotEquals(first.first { it.path == "plain.txt" }.bookId, changed.bookId)
    }

    @Test
    fun `identical files are one document, and reindex rebuilds`() = SqliteStore(File(tmp, "docs.db").path).use { store ->
        put("a/notes.md", "# Same\n\nText.\n")
        put("b/notes.md", "# Same\n\nText.\n")
        val r = Indexer(store).indexFolder(File(tmp, "docs"))
        assertEquals(listOf("a/notes.md INDEXED", "b/notes.md ALREADY_INDEXED"), show(r))
        assertEquals(r[0].bookId, r[1].bookId)
        val rebuilt = Indexer(store).indexFolder(File(tmp, "docs"), reindex = true)
        assertEquals(listOf("a/notes.md INDEXED", "b/notes.md INDEXED"), show(rebuilt))
        assertEquals(1, store.books().size)
    }

    @Test
    fun `an unreachable embedder stops the run before anything is stored`() = SqliteStore(File(tmp, "docs.db").path).use { store ->
        val down = object : Embedder by FakeEmbedder() {
            override fun checkAvailable() = throw RagException.EmbeddingBackendUnavailable("down")
        }
        assertFailsWith<RagException.EmbeddingBackendUnavailable> { Indexer(store).indexFolder(folder(), embedder = down) }
        assertEquals(0, store.books().size)
    }

    @Test
    fun `with an embedder, documents are embedded`() = SqliteStore(File(tmp, "docs.db").path).use { store ->
        put("notes.md", "# Notes\n\nFirst point.\n")
        val r = Indexer(store).indexFolder(File(tmp, "docs"), embedder = FakeEmbedder())
        assertEquals("complete", store.book(r.single().bookId!!)!!.embedStatus)
    }

    @Test
    fun `review fixes - the folder itself may be hidden or dot, and folder links are not followed`() = SqliteStore(File(tmp, "docs.db").path).use { store ->
        put("notes.md", "# Notes\n\nFirst point.\n")
        assertEquals(listOf("notes.md INDEXED"), show(Indexer(store).indexFolder(File(tmp, "docs/."))))
        File(tmp, "docs").renameTo(File(tmp, ".docs"))
        assertEquals(listOf("notes.md ALREADY_INDEXED"), show(Indexer(store).indexFolder(File(tmp, ".docs"))))
        File(tmp, "other").mkdirs()
        File(tmp, "other/far.md").writeText("# Far\n\nAway.\n")
        val linked = try {
            java.nio.file.Files.createSymbolicLink(File(tmp, ".docs/link").toPath(), File(tmp, "other").toPath()); true
        } catch (e: Exception) { false }
        if (linked) assertEquals(listOf("notes.md ALREADY_INDEXED"), show(Indexer(store).indexFolder(File(tmp, ".docs"))))
    }
}
