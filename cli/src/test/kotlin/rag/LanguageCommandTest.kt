package rag

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SPEC §19.4, Phase 10 step 4: `rag index --language` and the language in `rag books`. */
class LanguageCommandTest {
    @TempDir
    lateinit var tmp: File

    private val spanish = "La biblioteca lee cada libro una sola vez y guarda su texto con los capítulos, párrafos y frases que " +
        "encontró. Cuando haces una pregunta, busca las palabras que escribiste y también los pasajes que significan lo " +
        "mismo, y luego te muestra dónde están en el libro. "

    private fun db() = File(tmp, "docs.db").path

    @Test
    fun `books show the detected language, and --language overrides it`() {
        val notes = File(tmp, "notas.md").apply { writeText("# Notas\n\n" + spanish.repeat(3)) }
        val other = File(tmp, "otras.md").apply { writeText("# Otras\n\n" + spanish.repeat(4)) }
        assertEquals(0, IndexCommand().test("--file ${notes.path} --db ${db()} --no-embed").statusCode)
        assertEquals(0, IndexCommand().test("--file ${other.path} --db ${db()} --no-embed --language PT").statusCode)
        val books = BooksCommand().test("--db ${db()}").stdout.lines().filter { it.isNotBlank() && !it.startsWith(" ") }
        assertTrue(books[0].contains("[paper, es, "), books[0])
        assertTrue(books[1].contains("[paper, pt, "), books[1])
    }

    @Test
    fun `a language that isn't an ISO code is refused before anything is stored`() {
        val notes = File(tmp, "notas.md").apply { writeText("# Notas\n\n" + spanish) }
        val r = IndexCommand().test("--file ${notes.path} --db ${db()} --no-embed --language Spanish")
        assertNotEquals(0, r.statusCode)
        assertTrue(r.stderr.contains("not an ISO 639 language code"), r.stderr)
    }
}
