package rag.store

import org.junit.jupiter.api.io.TempDir
import rag.SampleBook
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.search.Mode
import rag.search.Searcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {

    @Test
    fun `a version 1 database migrates to the current version`(@TempDir dir: File) {
        val path = File(dir, "v1.db").path
        // The version 1 shape of the two tables that changed.
        SqlDriver.default().open(path).use { s ->
            s.execute("""CREATE TABLE book (id INTEGER PRIMARY KEY, file_sha256 TEXT NOT NULL UNIQUE, title TEXT, text TEXT NOT NULL,
                extractor TEXT NOT NULL, chapter_strategy TEXT NOT NULL, page_offset INTEGER, embed_model TEXT, embed_dim INTEGER,
                embed_status TEXT NOT NULL DEFAULT 'none', created_at TEXT NOT NULL)""")
            s.execute("CREATE TABLE page (book_id INTEGER NOT NULL, pdf_page INTEGER NOT NULL, char_start INTEGER NOT NULL, PRIMARY KEY (book_id, pdf_page))")
            s.execute("INSERT INTO book VALUES (1, 'sha', 'Old', 'text', 'x', 'outline', NULL, NULL, NULL, 'none', 'now')")
            s.execute("INSERT INTO page VALUES (1, 1, 0), (1, 2, 3)")
            s.execute("PRAGMA user_version = 1")
        }

        SqliteStore(path).use { store ->
            assertEquals(listOf("1", "2"), store.pageLabels(1))
            assertEquals("pdf" to "pdf", store.book(1)!!.let { it.format to it.pageKind })
            assertEquals("none", store.book(1)!!.summaryStatus)
            val summaryColumns = store.db.query("PRAGMA table_info(summary)") { it.string("name")!! }
            kotlin.test.assertTrue("char_start" in summaryColumns && "prompt" in summaryColumns && "depth" in summaryColumns)
            val version = store.db.query("PRAGMA user_version") { it.int(1) }.single()
            assertEquals(SqliteStore.SCHEMA_VERSION, version)
        }
        // Opening again is a no-op.
        SqliteStore(path).use { assertEquals(listOf("1", "2"), it.pageLabels(1)) }
    }

    @Test
    fun `a version 3 database migrates to several models per book`(@TempDir dir: File) {
        val path = File(dir, "v3.db").path
        val pdf = SampleBook.write(File(dir, "sample.pdf"))
        val before = SqliteStore(path).use { store ->
            val bookId = Indexer(store).index(pdf, embedder = FakeEmbedder()).bookId
            Searcher(store, FakeEmbedder()).search("bravo", mode = Mode.VECTOR).map { it.paragraph.unitKey } to bookId
        }
        // Back to the version 3 shape: one vector per unit, the model only on the book row.
        SqlDriver.default().open(path).use { s ->
            s.execute("ALTER TABLE embedding RENAME TO embedding_v4")
            s.execute("""CREATE TABLE embedding (unit_id INTEGER PRIMARY KEY REFERENCES unit(id) ON DELETE CASCADE, model TEXT NOT NULL,
                dim INTEGER NOT NULL, scale REAL NOT NULL, vec_i8 BLOB NOT NULL, vec_bits BLOB NOT NULL)""")
            s.execute("INSERT INTO embedding SELECT * FROM embedding_v4")
            s.execute("DROP TABLE embedding_v4")
            s.execute("DROP TABLE book_model")
            s.execute("PRAGMA user_version = 3")
        }

        SqliteStore(path).use { store ->
            val book = store.book(before.second)!!
            assertEquals(listOf(BookModel("fake-bow", 512, "complete")), book.models)
            val key = store.db.query("PRAGMA table_info(embedding)") { it.string("name")!! to it.int("pk") }.filter { it.second > 0 }.map { it.first }
            assertEquals(listOf("unit_id", "model"), key)
            assertEquals(before.first, Searcher(store, FakeEmbedder()).search("bravo", mode = Mode.VECTOR).map { it.paragraph.unitKey })
            assertEquals(SqliteStore.SCHEMA_VERSION, store.db.query("PRAGMA user_version") { it.int(1) }.single())
        }
        // Opening again is a no-op.
        SqliteStore(path).use { assertEquals(1, it.book(before.second)!!.models.size) }
    }
}
