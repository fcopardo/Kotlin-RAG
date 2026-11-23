package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan step 6: the edge cases the first attempt missed, one small file per family: braces inside strings,
 * raw strings and templates, lifetimes, next-line braces, multi-line signatures, and unbalanced files.
 */
@Timeout(10)
class EdgeCasesTest {
    private fun names(src: String, language: Language, kind: DeclarationKind? = null): List<String> {
        val d = DeclarationFinder.find(src, language)
        assertEquals(emptyList(), d.warnings, "$language warnings")
        return d.items.filter { kind == null || it.kind == kind }.map { it.name }
    }

    @Test
    fun `rust lifetimes are not character literals`() {
        val src = "fn longest<'a>(x: &'a str, y: &'a str) -> &'a str {\n    if x.len() > y.len() { x } else { y }\n}\n" +
            "struct Holder<'a> {\n    s: &'a str,\n}\nimpl<'a> Holder<'a> {\n    fn get(&self) -> &'a str {\n        let c = '{';\n        self.s\n    }\n}\n"
        assertEquals(listOf("longest", "get"), names(src, Language.RUST, DeclarationKind.FUNCTION))
    }

    @Test
    fun `kotlin raw strings and templates hold braces`() {
        val src = "fun json() = \"\"\"{\"a\": \"}\"}\"\"\"\nfun tmpl(x: Boolean) = \"v=\${ if (x) \"}\" else \"{\" }\"\n" +
            "class K {\n    fun f() {\n        val s = \"{\"\n    }\n}\n"
        assertEquals(listOf("json", "tmpl", "K", "f"), names(src, Language.KOTLIN))
    }

    @Test
    fun `js template literals hold braces and nested templates`() {
        val src = "const s = `\${ {a: 1}.a } }`;\nfunction f() {\n  return `{\${g()}`;\n}\nclass C {\n  m() { return '}'; }\n}\n"
        assertEquals(listOf("f", "C", "m"), names(src, Language.JS_TS))
    }

    @Test
    fun `c++ raw strings and character literals`() {
        val src = "const char* s = R\"x( { )x\";\nint f() {\n    return '{';\n}\n"
        assertEquals(listOf("f"), names(src, Language.C_CPP))
    }

    @Test
    fun `c# verbatim strings and next-line braces`() {
        val src = "class A\n{\n    string s = @\"{ \"\"\";\n    void M()\n    {\n    }\n}\n"
        assertEquals(listOf("A", "M"), names(src, Language.CSHARP))
    }

    @Test
    fun `swift interpolation holds a brace`() {
        assertEquals(listOf("f"), names("func f() -> String {\n    return \"\\(g()) }\"\n}\n", Language.SWIFT))
    }

    @Test
    fun `go raw strings and receivers`() {
        val fns = names("func f() string {\n\treturn `{`\n}\n\nfunc (s *S) M() {}\n", Language.GO, DeclarationKind.FUNCTION)
        assertEquals(2, fns.size, "$fns")
        assertEquals("f", fns.first())
        assertTrue(fns.last().endsWith("M"), "$fns")
    }

    @Test
    fun `python multi-line signature with a blank line inside the body`() {
        val src = "def f(\n    a,\n    b,\n):\n    s = '{'\n\n    return a\n"
        val d = DeclarationFinder.find(src, Language.PYTHON)
        assertEquals(listOf("f"), d.items.map { it.name })
        assertEquals(src.trimEnd().length - 1, d.items.single().end, "the body runs to the last line")
    }

    @Test
    fun `an unbalanced file never throws, and says so`() {
        mapOf(
            Language.KOTLIN to "fun f() {\n", Language.JAVA to "class A { void m() {\n", Language.JS_TS to "function f() {",
            Language.C_CPP to "int f() {\n}\n}\n", Language.CSHARP to "class A {", Language.GO to "func f() {",
            Language.RUST to "fn f() {", Language.SWIFT to "func f() {",
        ).forEach { (lang, src) ->
            val d = DeclarationFinder.find(src, lang)
            assertTrue(d.warnings.isNotEmpty(), "$lang should warn about $src")
        }
        assertEquals(emptyList(), DeclarationFinder.find("def f(:\n  x = (\n", Language.PYTHON).items.map { it.kind }.filter { it == DeclarationKind.CLASS })
    }
}
