package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Timeout(10)
class LinesTest {
    // a0 \n1 ' '2 ' '3 b4 c5 \n6 \n7 \t8 x9 (length 10)
    private val t = "a\n  bc\n\n\tx"
    private val lines = Lines(t)

    @Test
    fun `lines, starts and ends`() {
        assertEquals(4, lines.count)
        assertEquals(listOf(0 to 1, 2 to 6, 7 to 7, 8 to 10), (0 until 4).map { lines.start(it) to lines.end(it) })
    }

    @Test
    fun `the line of an offset - a line break belongs to its line, the text length to the last line`() {
        assertEquals(listOf(0, 0, 1, 1, 1, 1, 1, 2, 3, 3, 3), (0..10).map { lines.lineOf(it) })
    }

    @Test
    fun `indentation, first non-blank, blank`() {
        assertEquals(listOf(0, 2, 0, 1), (0 until 4).map { lines.indent(it) })
        assertEquals(listOf(0, 4, -1, 9), (0 until 4).map { lines.firstNonBlank(it, t) })
        assertEquals(listOf(false, false, true, false), (0 until 4).map { lines.isBlank(it, t) })
        val masked = "a\n    \n\n\tx"
        assertTrue(lines.isBlank(1, masked))
        assertEquals(-1, lines.firstNonBlank(1, masked))
    }

    @Test
    fun `empty text and a trailing line break`() {
        val empty = Lines("")
        assertEquals(1, empty.count)
        assertEquals(0 to 0, empty.start(0) to empty.end(0))
        assertEquals(0, empty.lineOf(0))
        val trailing = Lines("a\n")
        assertEquals(2, trailing.count)
        assertEquals(1, trailing.lineOf(2))
        assertEquals(2 to 2, trailing.start(1) to trailing.end(1))
    }

    @Test
    fun `a million lines`() {
        val big = "x\n".repeat(1_000_000)
        val started = System.nanoTime()
        val l = Lines(big)
        var sum = 0L
        for (i in big.indices step 7) sum += l.lineOf(i)
        assertEquals(1_000_001, l.count)
        assertTrue(sum > 0)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }
}
