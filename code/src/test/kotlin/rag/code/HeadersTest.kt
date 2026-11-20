package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(10)
class HeadersTest {
    /** The header of the last `{` in [t] (masked = original here), with the parent's `{` at [floor]. */
    private fun header(t: String, floor: Int = -1) = Headers.of(t, Lines(t), t.lastIndexOf('{'), floor)

    private fun text(t: String, floor: Int = -1) = header(t, floor).text

    @Test
    fun `one line`() {
        assertEquals(Header("class A", 0), header("class A {"))
        assertEquals(Header("fn f()", 2), header("  fn f() {"))
    }

    @Test
    fun `a brace on its own line takes the line above`() {
        assertEquals(Header("class A", 0), header("class A\n{"))
        assertEquals(Header("", 9), header("class A\n\n{"), "a blank line above is not taken")
    }

    @Test
    fun `a signature over several lines`() {
        assertEquals(Header("fn multi( a, b ): Int", 0), header("fn multi(\n    a,\n    b\n): Int {"))
    }

    @Test
    fun `continuation lines`() {
        assertEquals("fn sig() : Int", text("fn sig()\n    : Int {"))
        assertEquals("fn f() -> Int", text("fn f()\n  -> Int {"))
        assertEquals("class A extends B implements C", text("class A\n    extends B\n    implements C {"))
        assertEquals("fn f() where T : X", text("fn f()\n  where T : X {"))
    }

    @Test
    fun `a line above that ends open is taken - where clauses, trailing commas, colons, arrows`() {
        assertEquals("impl<T: Debug> Wrapper<T> where T: Clone,", text("impl<T: Debug> Wrapper<T>\nwhere\n    T: Clone,\n{"))
        assertEquals("class A implements B, C", text("class A implements B,\n    C {"))
        assertEquals("class A( val x: Int, ) : B(), C", text("class A(\n    val x: Int,\n) : B(),\n    C {"))
        assertEquals("class A : B", text("class A :\n    B {"))
        assertEquals("fn f() -> Result<(), E>", text("fn f() ->\n    Result<(), E> {"))
        assertEquals("class A implements B", text("class A implements\n    B {"))
        assertEquals("fn g()", text("let x = (a,\n  b);\nfn g() {"), "a statement ending in ; is never taken")
        assertEquals("INCH(2.54)", text("    CM(1) {\n    },\n    INCH(2.54) {"), "a line with a brace is never taken for ending open")
    }

    @Test
    fun `the statement on the line above is not taken`() {
        val t = "val p = 1\nfn f() {"
        assertEquals(Header("fn f()", t.indexOf("fn")), header(t))
    }

    @Test
    fun `same-line boundaries - semicolons and braces outside parentheses`() {
        assertEquals("fn second()", text("fn semi() { x(); y() }; fn second() {"))
        assertEquals("class C", text("class B {} class C {"))
        assertEquals("else", text("if (a) { b() } else {"))
        assertEquals("fn g(a = { 1 }, b = 2)", text("fn g(a = { 1 }, b = 2) {"))
    }

    @Test
    fun `a line ending a statement is not taken, and an empty header starts at the brace`() {
        val t = "x();\n{"
        assertEquals(Header("", t.indexOf('{')), header(t))
        assertEquals(Header("", 4), header("a }\n{"))
    }

    @Test
    fun `never at or before the parent's brace`() {
        val same = "class A { fn c1() {"
        assertEquals("fn c1()", text(same, floor = same.indexOf('{')))
        val multi = "class A {\n  fn f(\n  ) {"
        assertEquals("fn f( )", text(multi, floor = multi.indexOf('{')))
        val alone = "class A {\n{"
        assertEquals(Header("", alone.lastIndexOf('{')), header(alone, floor = alone.indexOf('{')))
        val after = "class A { fn f(\n) {"
        assertEquals("fn f( )", text(after, floor = after.indexOf('{')))
    }
}
