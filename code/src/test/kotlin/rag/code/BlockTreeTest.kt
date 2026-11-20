package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(10)
class BlockTreeTest {
    private fun build(masked: String, original: String = masked, warnings: MutableList<String> = mutableListOf()) =
        BlockTree.build(masked, original, Lines(original), warnings)

    /** `open..close` with children in brackets, `!` when opened in parentheses. */
    private fun show(blocks: List<Block>): String = blocks.joinToString(" ") { b ->
        "${b.open}..${b.close}${if (b.inParens) "!" else ""}" + if (b.children.isEmpty()) "" else "[${show(b.children)}]"
    }

    @Test
    fun `blocks nest, in order`() {
        // a0 {1 b2 {3 c4 }5 d6 }7 e8 {9 f10 }11
        assertEquals("1..7[3..5] 9..11", show(build("a{b{c}d}e{f}")))
    }

    @Test
    fun `parents point up`() {
        val roots = build("{{}}")
        assertEquals(null, roots.single().parent)
        assertEquals(roots.single(), roots.single().children.single().parent)
    }

    @Test
    fun `a brace opened inside parentheses is marked`() {
        // f0 (1 {2 x3 }4 )5 ' '6 {7 y8 }9
        assertEquals("2..4! 7..9", show(build("f({x}) {y}")))
        assertEquals("3..5", show(build("))){a}")), "the count never goes below zero")
    }

    @Test
    fun `an unmatched closing brace is ignored, with a warning`() {
        val warnings = mutableListOf<String>()
        assertEquals("0..1 5..6", show(build("{}\n}\n{}", warnings = warnings)))
        assertEquals(listOf("line 2: unmatched }"), warnings)
    }

    @Test
    fun `blocks left open close at the last non-whitespace character, with warnings`() {
        // {0 \n1 ' '2 {3 \n4 ' '5 {6 \n7 ' '8 ' '9 \n10
        val warnings = mutableListOf<String>()
        assertEquals("0..6[3..6[6..6]]", show(build("{\n {\n {\n  \n", warnings = warnings)))
        assertEquals(listOf("line 1: unclosed {", "line 2: unclosed {", "line 3: unclosed {"), warnings)
    }

    @Test
    fun `braces come from the masked text, the last character from the original`() {
        val original = "{ // }"
        val masked = "{     "
        val warnings = mutableListOf<String>()
        assertEquals("0..5", show(build(masked, original, warnings)))
        assertEquals(listOf("line 1: unclosed {"), warnings)
    }

    @Test
    fun `ten thousand levels deep`() {
        val t = "{".repeat(10_000) + "}".repeat(10_000)
        var b = build(t).single()
        var depth = 1
        while (b.children.isNotEmpty()) {
            b = b.children.single()
            depth++
        }
        assertEquals(10_000, depth)
        assertEquals(9_999 to 10_000, b.open to b.close)
    }
}
