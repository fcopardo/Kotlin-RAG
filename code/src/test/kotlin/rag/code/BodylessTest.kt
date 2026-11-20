package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(10)
class BodylessTest {
    private object Toy : FamilyRules {
        private val bodyless = Regex("""^fn (\w+)\s*\([^)]*\)\s*=""")
        override fun classify(header: String): Classified? = null
        override fun bodylessFunction(statement: String): String? = bodyless.find(statement)?.groupValues?.get(1)
        override val annotationPrefixes = listOf("@")
    }

    /** Bodyless functions of the region [from, to) of [t], its child blocks taken from [BlockTree]. */
    private fun find(t: String, from: Int = 0, to: Int = t.length, children: List<Block>? = null): List<BodylessFunction> {
        val masked = MaskingLexer.mask(t, Language.KOTLIN).text
        val lines = Lines(t)
        val blocks = children ?: BlockTree.build(masked, t, lines, mutableListOf())
        return Bodyless.find(masked, t, lines, from, to, blocks, Toy)
    }

    private fun String.endOf(s: String) = indexOf(s) + s.length - 1

    @Test
    fun `one line, and a function with braces is not one`() {
        val t = "fn a() = 1\nfn b() {}"
        assertEquals(listOf(BodylessFunction("a", 0, t.endOf("= 1"))), find(t))
    }

    @Test
    fun `deeper lines belong to it, blank lines are passed over, the end skips trailing blanks`() {
        val t = "fn two() =\n    listOf(1)\n\nfn x() {}"
        assertEquals(listOf(BodylessFunction("two", 0, t.endOf("listOf(1)"))), find(t))
    }

    @Test
    fun `a block opened within it keeps it going to its closing brace`() {
        val t = "fn three() = run {\n    x()\n}\nfn y() {}"
        assertEquals(listOf(BodylessFunction("three", 0, t.endOf("x()\n}"))), find(t))
    }

    @Test
    fun `a comment line stops it, a trailing comment is not part of it`() {
        val t = "fn a() = 1 // t\n// doc\nfn b() = 2"
        assertEquals(listOf(BodylessFunction("a", 0, t.endOf("fn a() = 1")), BodylessFunction("b", t.indexOf("fn b"), t.length - 1)), find(t))
    }

    @Test
    fun `a statement over several lines`() {
        val t = "fn p(\n  a\n) = a\nfn q() {}"
        assertEquals(listOf(BodylessFunction("p", 0, t.endOf(") = a"))), find(t))
    }

    @Test
    fun `inside a class region, never past its closing brace`() {
        val t = "class A {\nfn f() = 1\n   }"
        val block = BlockTree.build(t, t, Lines(t), mutableListOf()).single()
        assertEquals(listOf(BodylessFunction("f", t.indexOf("fn f"), t.endOf("= 1"))), find(t, block.open + 1, block.close, block.children))
    }

    @Test
    fun `lines inside child blocks are not in the region`() {
        assertEquals(emptyList(), find("class A {\n  fn inner() = 1\n}"))
    }

    @Test
    fun `twenty thousand of them in one region`() {
        val t = (1..20_000).joinToString("") { "fn f$it() = $it\nfn g$it() { x() }\n" }
        val found = find(t)
        assertEquals(20_000, found.size)
        assertEquals("f20000", found.last().name)
    }
}
