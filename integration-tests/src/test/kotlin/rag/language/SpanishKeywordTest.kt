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
import kotlin.test.assertTrue

/** Phase 10 step 6 (SPEC §19.4): keyword search in Spanish, with English results unchanged. */
@Timeout(120)
class SpanishKeywordTest {
    @TempDir
    lateinit var dir: File

    private val epub = File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub")
    private val filler = "El viaje duró tres días y durante ese tiempo los viajeros hablaron de muchas cosas que habían visto " +
        "en el camino hacia el norte, donde el invierno era más largo y las noches eran más frías que en su tierra."

    private fun spanish() = File(dir, "cronica.md").apply {
        writeText(
            "# Crónica\n\n$filler\n\nLas bibliotecas públicas guardaban miles de libros antiguos en sus sótanos.\n\n" +
                "Ella preguntaba siempre por la búsqueda de un manuscrito perdido.\n\n$filler\n",
        )
    }

    private fun keys(store: SqliteStore, query: String, languages: List<String>? = null, queryLanguage: String? = null) =
        Searcher(store, null).search(query, 10, mode = Mode.LEXICAL, languages = languages, queryLanguage = queryLanguage)

    @Test
    fun `a Spanish word finds its other forms and its unaccented spelling`() {
        SqliteStore(File(dir, "lib.db").path).use { store ->
            Indexer(store).index(spanish())
            assertEquals("es", store.books().single().language)
            val singular = keys(store, "biblioteca")
            assertTrue(singular.isNotEmpty() && singular.first().paragraph.text.contains("bibliotecas"), singular.map { it.paragraph.text }.toString())
            assertTrue(keys(store, "preguntar").first().paragraph.text.contains("preguntaba"))
            assertTrue(keys(store, "busqueda").first().paragraph.text.contains("búsqueda"))
            // The closest phrase comes from the Spanish phrase table too.
            assertTrue(keys(store, "manuscritos perdidos").first().phrase.text.contains("manuscrito perdido"))
        }
    }

    @Test
    fun `English results don't change when a Spanish document joins the library`() {
        val queries = listOf("spies", "attack by stratagem", "the art of war is of vital importance", "terrain")
        val alone = SqliteStore(File(dir, "en.db").path).use { store ->
            Indexer(store).index(epub)
            queries.map { q -> keys(store, q).map { it.paragraph.unitKey } }
        }
        val mixed = SqliteStore(File(dir, "mixed.db").path).use { store ->
            Indexer(store).index(epub)
            Indexer(store).index(spanish())
            queries.map { q -> keys(store, q, languages = listOf("en")).map { it.paragraph.unitKey } }
        }
        assertEquals(alone, mixed)
    }

    @Test
    fun `the query's language decides how it is prepared`() {
        SqliteStore(File(dir, "lib.db").path).use { store ->
            Indexer(store).index(spanish())
            // As English, "preguntar" isn't stemmed the Spanish way, so it doesn't reach "preguntaba".
            assertTrue(keys(store, "preguntar", queryLanguage = "en").isEmpty())
            assertTrue(keys(store, "preguntar", queryLanguage = "es").isNotEmpty())
            // A one-language search uses that language.
            assertTrue(keys(store, "preguntar", languages = listOf("es")).isNotEmpty())
        }
    }

    @Test
    fun `a configured default language counts as the caller stating it`() {
        SqliteStore(File(dir, "lib.db").path).use { store ->
            Indexer(store).index(spanish())
            val asEnglish = Searcher(store, null, defaultLanguage = "en").search("preguntar", 10, mode = Mode.LEXICAL)
            val asSpanish = Searcher(store, null, defaultLanguage = "es").search("preguntar", 10, mode = Mode.LEXICAL)
            assertTrue(asEnglish.isEmpty())
            assertTrue(asSpanish.isNotEmpty())
        }
    }

    @Test
    fun `a version 8 database moves Spanish documents to the Spanish keyword tables`() {
        val path = File(dir, "v8.db").path
        SqliteStore(path).use { store ->
            Indexer(store).index(epub)
            Indexer(store).index(spanish())
        }
        // Back to the version 8 shape: the Spanish book's keyword rows in the porter tables, as written.
        SqlDriver.default().open(path).use { s ->
            for ((kind, table) in listOf("g" to "para", "p" to "phrase")) {
                s.execute("DELETE FROM ${table}_fts_x WHERE rowid IN (SELECT id FROM unit WHERE book_id = 2 AND kind = '$kind')")
                s.execute(
                    "INSERT INTO ${table}_fts(rowid, text) SELECT u.id, substr(b.text, u.char_start + 1, u.char_end - u.char_start + 1) " +
                        "FROM unit u JOIN book b ON b.id = u.book_id WHERE u.book_id = 2 AND u.kind = '$kind'",
                )
            }
            s.execute("PRAGMA user_version = 8")
        }
        SqliteStore(path).use { store ->
            assertEquals(SqliteStore.SCHEMA_VERSION, store.db.query("PRAGMA user_version") { it.int(1) }.single())
            assertTrue(store.ftsSearch("para_fts", "\"bibliotecas\"").isEmpty(), "no Spanish rows left in the porter table")
            // An English and a Spanish book: a one-word query's language can't be detected, so it is stated.
            assertTrue(keys(store, "biblioteca", queryLanguage = "es").first().paragraph.text.contains("bibliotecas"))
            assertTrue(keys(store, "spies").all { it.language == "en" })
        }
    }
}
