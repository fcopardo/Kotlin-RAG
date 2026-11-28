package rag.language

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import rag.index.Indexer
import rag.search.Mode
import rag.search.Searcher
import rag.store.SqliteStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Phase 10 step 5 (SPEC §19.4): searches limited to a set of languages; code always matches. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class LanguageFilterTest {
    private lateinit var dir: File
    private lateinit var store: SqliteStore
    private val searcher get() = Searcher(store, null)

    private val spanish = "La biblioteca lee cada libro una sola vez y guarda su texto con los capítulos, párrafos y frases que " +
        "encontró. Cuando haces una pregunta, busca las palabras que escribiste y también los pasajes que significan lo mismo. "
    private val portuguese = "A biblioteca lê cada livro uma única vez e guarda o seu texto com os capítulos, parágrafos e frases que " +
        "encontrou. Quando você faz uma pergunta, ela procura as palavras que você escreveu e também os trechos que significam. "

    @BeforeAll
    fun index() {
        dir = Files.createTempDirectory("rag-language-filter").toFile()
        store = SqliteStore(File(dir, "lib.db").path)
        val indexer = Indexer(store)
        indexer.index(File("src/test/resources/epub/sun-tzu_the-art-of-war_lionel-giles.epub"))
        indexer.index(File(dir, "notas.md").apply { writeText("# Notas\n\n" + spanish.repeat(4)) })
        indexer.index(File(dir, "notas-pt.md").apply { writeText("# Notas\n\n" + portuguese.repeat(4)) })
        indexer.index(File(dir, "game").apply {
            mkdirs()
            File(this, "Library.kt").writeText("package game\n\n/** The biblioteca of spies: every book read once. */\nfun biblioteca(spies: List<String>): Int = spies.size\n")
        })
        assertEquals(listOf("en", "es", "pt", DocumentLanguages.NONE), store.books().map { it.language })
    }

    @AfterAll
    fun cleanUp() {
        store.close()
        dir.deleteRecursively()
    }

    private fun languagesOf(query: String, languages: List<String>?) =
        searcher.search(query, 20, mode = Mode.LEXICAL, languages = languages).map { it.language }.toSet()

    @Test
    fun `no filter searches every language`() {
        assertEquals(setOf("es", "pt", DocumentLanguages.NONE), languagesOf("biblioteca", null))
    }

    @Test
    fun `one language keeps that language and code`() {
        assertEquals(setOf("es", DocumentLanguages.NONE), languagesOf("biblioteca", listOf("es")))
        assertEquals(setOf("pt", DocumentLanguages.NONE), languagesOf("biblioteca", listOf("pt")))
        assertEquals(setOf(DocumentLanguages.NONE), languagesOf("biblioteca", listOf("en")))
        assertEquals(setOf("en", DocumentLanguages.NONE), languagesOf("spies", listOf("en")))
        assertEquals(setOf(DocumentLanguages.NONE), languagesOf("spies", listOf("es")))
    }

    @Test
    fun `several languages, written any case`() {
        assertEquals(setOf("es", "pt", DocumentLanguages.NONE), languagesOf("biblioteca", listOf("ES", " pt ")))
    }

    @Test
    fun `a language with no documents finds only code`() {
        assertEquals(setOf(DocumentLanguages.NONE), languagesOf("biblioteca", listOf("fr")))
    }

    @Test
    fun `search by kind takes the same filter`() {
        val byKind = searcher.searchByKind("biblioteca", 3, mode = Mode.LEXICAL, languages = listOf("es"))
        assertEquals(listOf("paper", "code"), byKind.keys.toList())
        assertEquals(setOf("es"), byKind.getValue("paper").map { it.language }.toSet())
        assertTrue(searcher.searchByKind("biblioteca", 3, mode = Mode.LEXICAL, languages = listOf("en")).keys == setOf("code"))
    }

    @Test
    fun `a code that isn't ISO 639 is refused`() {
        assertFailsWith<IllegalArgumentException> { searcher.search("biblioteca", languages = listOf("spanish")) }
    }
}
