package rag.store

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {

    @Test
    fun `a version 1 database gets formats, page kinds and page labels`(@TempDir dir: File) {
        val path = File(dir, "v1.db").path
        // The version 1 shape of the two tables that changed.
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.execute("""CREATE TABLE book (id INTEGER PRIMARY KEY, file_sha256 TEXT NOT NULL UNIQUE, title TEXT, text TEXT NOT NULL,
                    extractor TEXT NOT NULL, chapter_strategy TEXT NOT NULL, page_offset INTEGER, embed_model TEXT, embed_dim INTEGER,
                    embed_status TEXT NOT NULL DEFAULT 'none', created_at TEXT NOT NULL)""")
                s.execute("CREATE TABLE page (book_id INTEGER NOT NULL, pdf_page INTEGER NOT NULL, char_start INTEGER NOT NULL, PRIMARY KEY (book_id, pdf_page))")
                s.execute("INSERT INTO book VALUES (1, 'sha', 'Old', 'text', 'x', 'outline', NULL, NULL, NULL, 'none', 'now')")
                s.execute("INSERT INTO page VALUES (1, 1, 0), (1, 2, 3)")
                s.execute("PRAGMA user_version = 1")
            }
        }

        SqliteStore(path).use { store ->
            assertEquals(listOf("1", "2"), store.pageLabels(1))
            assertEquals("pdf" to "pdf", store.book(1)!!.let { it.format to it.pageKind })
            val version = store.connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } }
            assertEquals(SqliteStore.SCHEMA_VERSION, version)
        }
        // Opening again is a no-op.
        SqliteStore(path).use { assertEquals(listOf("1", "2"), it.pageLabels(1)) }
    }
}
