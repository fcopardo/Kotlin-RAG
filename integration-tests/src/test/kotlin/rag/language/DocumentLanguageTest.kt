package rag.language

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import rag.index.Indexer
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqlDriver
import rag.store.SqliteStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Phase 10 step 4 (SPEC §19.4): each document's language in the store, on its hits, and after a migration. */
@Timeout(120)
class DocumentLanguageTest {
    @TempDir
    lateinit var dir: File

    private val epub = File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")
    private val spanish = "La biblioteca lee cada libro una sola vez y guarda su texto con los capítulos, párrafos y frases que " +
        "encontró. Cuando haces una pregunta, busca las palabras que escribiste y también los pasajes que significan lo " +
        "mismo, y luego te muestra dónde están en el libro. Nada se envía a un servicio remoto. "

    private fun spanishNotes() = File(dir, "notas.md").apply { writeText("# Notas de estrategia\n\n" + spanish.repeat(4)) }
    private fun project() = File(dir, "game").apply {
        mkdirs()
        File(this, "Strategy.kt").writeText("package game\n\n/** Picks the next move. */\nfun nextMove(board: List<Int>): Int = board.indexOf(0)\n")
    }

    private fun languages(store: SqliteStore) = store.books().map { it.language }

    @Test
    fun `a book, a Spanish note and a code project get en, es and zxx`() {
        SqliteStore(File(dir, "lib.db").path).use { store ->
            val indexer = Indexer(store)
            indexer.index(epub)
            indexer.index(spanishNotes())
            indexer.index(project())
            assertEquals(listOf("en", "es", DocumentLanguages.NONE), languages(store))
        }
    }

    @Test
    fun `an override wins, is lower-cased, and a bad one is refused before anything is stored`() {
        SqliteStore(File(dir, "lib.db").path).use { store ->
            val indexer = Indexer(store)
            indexer.index(spanishNotes(), language = "PT")
            assertEquals(listOf("pt"), languages(store))
            assertFailsWith<IllegalArgumentException> { indexer.index(epub, language = "English") }
            assertEquals(1, store.books().size)
            // Reindexing with another language replaces it.
            indexer.index(spanishNotes(), reindex = true, language = "es")
            assertEquals(listOf("es"), languages(store))
        }
    }

    @Test
    fun `a folder override applies to every file`() {
        val folder = File(dir, "docs").apply { mkdirs() }
        spanishNotes().copyTo(File(folder, "a.md"))
        File(folder, "b.md").writeText("# Plain\n\n" + "The library reads each book once and stores its text with the chapters it found. ".repeat(6))
        SqliteStore(File(dir, "lib.db").path).use { store ->
            Indexer(store).indexFolder(folder, language = "es")
            assertEquals(listOf("es", "es"), languages(store))
        }
    }

    @Test
    fun `hits carry their document's language`() {
        SqliteStore(File(dir, "lib.db").path).use { store ->
            val indexer = Indexer(store)
            indexer.index(epub)
            indexer.index(spanishNotes())
            val searcher = Searcher(store, null)
            assertEquals(setOf("en"), searcher.search("spies", 5, mode = Mode.LEXICAL).map { it.language }.toSet())
            assertEquals(setOf("es"), searcher.search("biblioteca capítulos", 5, mode = Mode.LEXICAL).map { it.language }.toSet())
        }
    }

    @Test
    fun `a version 7 database gains each document's detected language`() {
        val path = File(dir, "v7.db").path
        val keys = SqliteStore(path).use { store ->
            val indexer = Indexer(store)
            indexer.index(epub)
            indexer.index(spanishNotes())
            indexer.index(project())
            Searcher(store, null).search("spies", 5, mode = Mode.LEXICAL).map { it.paragraph.unitKey }
        }
        // Back to the version 7 shape: no language column.
        SqlDriver.default().open(path).use { s ->
            s.execute("ALTER TABLE book DROP COLUMN language")
            s.execute("PRAGMA user_version = 7")
        }
        SqliteStore(path).use { store ->
            assertEquals(listOf("en", "es", DocumentLanguages.NONE), languages(store))
            assertEquals(SqliteStore.SCHEMA_VERSION, store.db.query("PRAGMA user_version") { it.int(1) }.single())
            assertEquals(keys, Searcher(store, null).search("spies", 5, mode = Mode.LEXICAL).map { it.paragraph.unitKey })
        }
        SqliteStore(path).use { assertTrue(languages(it).isNotEmpty(), "opening again is a no-op") }
    }
}
