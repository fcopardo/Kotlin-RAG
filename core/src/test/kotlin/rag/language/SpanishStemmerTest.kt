package rag.language

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** [SpanishStemmer] against Snowball's own reference output (`snowball/spanish-voc-output.tsv`, 7,095 words). */
@Timeout(30)
class SpanishStemmerTest {
    private val pairs = javaClass.getResource("/snowball/spanish-voc-output.tsv")!!.readText().lines()
        .filter { it.isNotBlank() }.map { it.split('\t').let { (w, s) -> w to s } }

    @Test
    fun `every reference word gets Snowball's stem`() {
        assertTrue(pairs.size > 7000)
        val wrong = pairs.mapNotNull { (word, expected) ->
            val got = runCatching { SpanishStemmer.stem(word) }.getOrElse { e -> "<${e.javaClass.simpleName}>" }
            if (got == expected) null else "$word: expected '$expected', got '$got'"
        }
        if (wrong.isNotEmpty()) fail("${wrong.size} of ${pairs.size} words differ, e.g.\n" + wrong.take(40).joinToString("\n"))
    }

    @Test
    fun `forms of one word share a stem`() {
        assertEquals(SpanishStemmer.stem("biblioteca"), SpanishStemmer.stem("bibliotecas"))
        assertEquals(SpanishStemmer.stem("pregunta"), SpanishStemmer.stem("preguntaba"))
        assertEquals(SpanishStemmer.stem("corrutina"), SpanishStemmer.stem("corrutinas"))
        assertEquals(SpanishStemmer.stem("búsqueda"), SpanishStemmer.stem("busqueda"))
    }

    @Test
    fun `short and odd words don't break it`() {
        listOf("", "a", "y", "él", "ü", "12", "x1", "aaaa", "llllll").forEach { SpanishStemmer.stem(it) }
        assertEquals("", SpanishStemmer.stem(""))
    }
}
