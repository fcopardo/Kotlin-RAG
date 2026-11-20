package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(10)
class DocsTest {
    private val t = """
        |class A {
        |    /**
        |     * Doc { with a brace }.
        |     */
        |    @Ann
        |    @Other(1)
        |    fn f() {}
        |
        |    // a gap follows
        |
        |    fn g() {}
        |    // direct
        |    fn h() {}
        |}
    """.trimMargin()
    private val masked = MaskingLexer.mask(t, Language.KOTLIN).text
    private val lines = Lines(t)

    private fun start(of: String, floor: Int = -1) = Docs.start(masked, t, lines, t.indexOf(of), floor, listOf("@"))

    @Test
    fun `doc comment and annotation lines directly above are taken`() {
        assertEquals(t.indexOf("/**"), start("fn f"))
    }

    @Test
    fun `a blank line stops it`() {
        assertEquals(t.indexOf("fn g"), start("fn g"))
    }

    @Test
    fun `a line comment directly above is taken, and code above it is not`() {
        assertEquals(t.indexOf("// direct"), start("fn h"))
        assertEquals(0, start("class A"))
    }

    @Test
    fun `never onto the floor's line or above`() {
        assertEquals(t.indexOf("fn h"), start("fn h", floor = t.indexOf("// direct")))
        assertEquals(t.indexOf("@Ann"), start("fn f", floor = t.indexOf("*/")))
    }

    @Test
    fun `other annotation prefixes`() {
        val rust = "#[derive(Debug)]\n#[repr(C)]\nstruct S {}"
        val m = MaskingLexer.mask(rust, Language.RUST).text
        assertEquals(0, Docs.start(m, rust, Lines(rust), rust.indexOf("struct"), -1, listOf("#[")))
        assertEquals(rust.indexOf("struct"), Docs.start(m, rust, Lines(rust), rust.indexOf("struct"), -1, listOf("@")))
    }
}
