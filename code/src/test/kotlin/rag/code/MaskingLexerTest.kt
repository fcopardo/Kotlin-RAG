package rag.code

import rag.code.Language.CSHARP
import rag.code.Language.C_CPP
import rag.code.Language.GO
import rag.code.Language.JAVA
import rag.code.Language.JS_TS
import rag.code.Language.KOTLIN
import rag.code.Language.PYTHON
import rag.code.Language.RUST
import rag.code.Language.SWIFT
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The masking lexer (plan step 2a). Each case is built from pieces kept as they are ([c]) and pieces
 * that must come out blank ([m]): spaces, with line breaks kept. A lexer that loops forever fails
 * its test after 10 seconds instead of hanging the build.
 */
@Timeout(10)
class MaskingLexerTest {

    private class Part(val s: String, val masked: Boolean)
    private fun c(s: String) = Part(s, false)
    private fun m(s: String) = Part(s, true)

    private fun blank(s: String) = buildString { s.forEach { append(if (it == '\n') '\n' else ' ') } }

    /** Spaces shown as `·` so a failure shows exactly which characters were masked. */
    private fun visible(s: String) = s.replace(' ', '·')

    private fun check(language: Language, vararg parts: Part) {
        val input = parts.joinToString("") { it.s }
        val expected = parts.joinToString("") { if (it.masked) blank(it.s) else it.s }
        val result = MaskingLexer.mask(input, language)
        assertEquals(visible(expected), visible(result.text), "$language, input:\n$input")
        assertEquals(emptyList(), result.warnings, "$language, input:\n$input")
    }

    private val cFamily = listOf(KOTLIN, JAVA, JS_TS, C_CPP, CSHARP, GO, RUST, SWIFT)

    // --- shared by the C family ---

    @Test
    fun `line comments end at the line break, which is kept`() = cFamily.forEach {
        check(it, c("f() { "), m("// } not code"), c("\n}"))
    }

    @Test
    fun `block comments, across lines`() = cFamily.forEach {
        check(it, c("x = 1; "), m("/* { \n } */"), c(" y();"))
    }

    @Test
    fun `double-quoted strings with an escaped quote`() = cFamily.forEach {
        check(it, c("s = "), m("\"a \\\" { b\""), c("; }"))
    }

    @Test
    fun `an escaped backslash ends before the closing quote`() = cFamily.forEach {
        check(it, c("s = "), m("\"a\\\\\""), c("; t = "), m("\"{\""), c(";"))
    }

    @Test
    fun `character literals, including quote and backslash`() = (cFamily - SWIFT).forEach {
        check(it, c("c = "), m("'{'"), c("; d = "), m("'\\''"), c("; e = "), m("'\\\\'"), c("; {}"))
    }

    @Test
    fun `block comments do not nest in Java, JS, C, C# and Go`() = listOf(JAVA, JS_TS, C_CPP, CSHARP, GO).forEach {
        check(it, c("x "), m("/* a /* b */"), c(" c; y"))
    }

    @Test
    fun `block comments nest in Kotlin, Rust and Swift`() = listOf(KOTLIN, RUST, SWIFT).forEach {
        check(it, c("x "), m("/* a /* b */ c */"), c(" y"))
    }

    @Test
    fun `slashes and stars that are not comments are code`() = cFamily.forEach {
        check(it, c("a = b / c * d; e = f / g; {}"))
    }

    // --- Kotlin ---

    @Test
    fun `Kotlin raw strings`() {
        check(KOTLIN, c("val s = "), m("\"\"\"a \" { \"\" b\"\"\""), c("\n{}"))
        check(KOTLIN, c("val s = "), m("\"\"\"line1 {\nline2 }\"\"\""), c("\nfun f() {}"))
        check(KOTLIN, c("val p = "), m("\"\"\"C:\\dir\\\"\"\""), c(" {}"))
    }

    @Test
    fun `Kotlin templates hold nested strings that don't end the outer one`() {
        check(KOTLIN, c("val s = "), m("\"x \${if (a) \"}\" else \"{\"} y\""), c(" + t"))
        check(KOTLIN, c("val s = "), m("\"\"\"\${\"}\"}\"\"\""), c(" {}"))
        check(KOTLIN, c("val s = "), m("\"\${m[\"k\"]?.let { \"\${it}\" }}\""), c(" {}"))
        check(KOTLIN, c("val s = "), m("\"\$name {\""), c("; }"))
    }

    // --- Java ---

    @Test
    fun `Java text blocks and a double quote in a char`() {
        check(JAVA, c("String s = "), m("\"\"\"\n  { \"quoted\" }\n  \"\"\""), c(";\nvoid f() {}"))
        check(JAVA, c("char q = "), m("'\"'"), c("; {"))
    }

    // --- JS / TS ---

    @Test
    fun `JS template literals nest`() {
        check(JS_TS, c("const s = "), m("`a \${b ? `{` : '}'} c`"), c("; {}"))
        check(JS_TS, c("const s = "), m("`line {\n}`"), c("\nfunction f() {}"))
        check(JS_TS, c("const s = "), m("`\${ {a: 1}.a }`"), c("; {}"))
    }

    @Test
    fun `JS regex literals are masked where an expression starts, division is not`() {
        check(JS_TS, c("const re = "), m("/[{]\\//"), c("; {}"))
        check(JS_TS, c("f("), m("/}/"), c(", y)"))
        check(JS_TS, c("return "), m("/{/"), c(".source"))
        check(JS_TS, c("const q = a / b / c; {}"))
        check(JS_TS, c("const r = (a + 1) / 2 / x[0]; {}"))
    }

    // --- C / C++ ---

    @Test
    fun `C++ raw strings`() {
        check(C_CPP, c("auto s = "), m("R\"x(a \" ) { )x\""), c("; {}"))
        check(C_CPP, c("auto s = "), m("u8R\"(})\""), c("; {}"))
        check(C_CPP, c("auto s = "), m("LR\"--(\n}\n)--\""), c(";\nint f() {}"))
    }

    @Test
    fun `C++ digit separators are not char literals`() {
        check(C_CPP, c("int n = 1'000'000; {}"))
        check(C_CPP, c("auto h = 0xFF'FF; char c = "), m("'}'"), c("; {}"))
    }

    @Test
    fun `C include paths in quotes are strings`() {
        check(C_CPP, c("#include "), m("\"a{.h\""), c("\nint main() {}"))
    }

    // --- C# ---

    @Test
    fun `C# verbatim, interpolated and raw strings`() {
        check(CSHARP, c("var a = "), m("@\"a \"\" { b\""), c("; {}"))
        check(CSHARP, c("var b = "), m("\$\"a {f(\"}\")} c\""), c("; {}"))
        check(CSHARP, c("var d = "), m("\$@\"a {x} \"\" }}\""), c("; {}"))
        check(CSHARP, c("var e = "), m("\"\"\"a \" { b\"\"\""), c("; {}"))
        check(CSHARP, c("var f = "), m("\$\"{{literal}} {x}\""), c("; {}"))
    }

    // --- Go ---

    @Test
    fun `Go raw strings and runes`() {
        check(GO, c("s := "), m("`a \" { \n b`"), c("\nfunc f() {}"))
        check(GO, c("r := "), m("'\\u007b'"), c("; {}"))
    }

    // --- Rust ---

    @Test
    fun `Rust raw and byte strings`() {
        check(RUST, c("let s = "), m("r#\"a \" { b\"#"), c("; {}"))
        check(RUST, c("let s = "), m("r##\"a \"# { b\"##"), c("; {}"))
        check(RUST, c("let b = "), m("b\"{\""), c("; let c = "), m("b'}'"), c("; {}"))
        check(RUST, c("let s = "), m("br#\"}\"#"), c("; {}"))
        check(RUST, c("let t = "), m("r\"\\\""), c("; {}"))
        check(RUST, c("let u = "), m("\"a\n{\""), c(";\nfn f() {}"))
    }

    @Test
    fun `Rust lifetimes are code, char literals are masked`() {
        check(RUST, c("fn f<'a>(x: &'a str) -> &'a str { x }"))
        check(RUST, c("fn g() -> &'static str { "), m("\"}\""), c(" }"))
        check(RUST, c("impl<'a> X<'a> { const C: char = "), m("'}'"), c("; const D: char = "), m("'\\u{7b}'"), c("; }"))
        check(RUST, c("'outer: loop { break 'outer; }"))
    }

    // --- Swift ---

    @Test
    fun `Swift multi-line, raw and interpolated strings`() {
        check(SWIFT, c("let s = "), m("\"\"\"\n  { \"x\" }\n  \"\"\""), c("\nfunc f() {}"))
        check(SWIFT, c("let r = "), m("#\"a \" { b\"#"), c("; {}"))
        check(SWIFT, c("let t = "), m("\"a \\(f(\"}\")) b\""), c("; {}"))
    }

    // --- Python ---

    @Test
    fun `Python comments, and a hash inside a string is not one`() {
        check(PYTHON, c("def f():\n    x = 1  "), m("# { not code"), c("\n    return x"))
        check(PYTHON, c("s = "), m("'# not a comment'"), c(" + t"))
    }

    @Test
    fun `Python quotes, escapes and triple quotes`() {
        check(PYTHON, c("a = "), m("'{'"), c(" + "), m("\"}\""), c(" + "), m("'it\\'s {'"))
        check(PYTHON, c("def g():\n    "), m("\"\"\"Doc {\n    more }\"\"\""), c("\n    pass"))
        check(PYTHON, c("x = "), m("'''a ' '' {'''"), c("\n"))
    }

    @Test
    fun `Python string prefixes are part of the literal`() {
        check(PYTHON, c("y = "), m("f\"{v}\""), c(" + "), m("rb'\\d{'"), c(" + "), m("R\"x\""), c(" + "), m("Br'y'"))
        check(PYTHON, c("z = "), m("f'''{a}\n}'''"), c("\nclass C:\n    pass"))
    }

    // --- everything ---

    @Test
    fun `other files are left as they are`() {
        val text = "key = \"value {\" // not a comment\n# nor this\n"
        val result = MaskingLexer.mask(text, Language.OTHER)
        assertEquals(text, result.text)
        assertEquals(emptyList(), result.warnings)
    }

    @Test
    fun `empty text`() = Language.entries.forEach {
        assertEquals("", MaskingLexer.mask("", it).text, "$it")
    }

    @Test
    fun `masking never moves a character or a line break, in any language`() {
        val soup = "fun f() { val s = \"a{\" // c\n /* d */ 'x' `y` r#\"z\"# \"\"\"t\n\"\"\" # p @\"q\" \$\"{r}\"\n}\n'\n\"\n/*"
        Language.entries.forEach { language ->
            val out = MaskingLexer.mask(soup, language).text
            assertEquals(soup.length, out.length, "$language: length")
            soup.indices.forEach { i ->
                assertEquals(soup[i] == '\n', out[i] == '\n', "$language: line break at $i")
                assertTrue(out[i] == soup[i] || out[i] == ' ', "$language: char at $i changed to '${out[i]}'")
            }
        }
    }

    @Test
    fun `an unterminated comment or literal is masked to the end and reported with its line`() {
        listOf(KOTLIN, JAVA, C_CPP, RUST).forEach {
            val r = MaskingLexer.mask("x = 1;\ny = 2; /* never closed {\n}", it)
            assertEquals(visible("x = 1;\ny = 2; " + blank("/* never closed {\n}")), visible(r.text), "$it")
            assertEquals(1, r.warnings.size, "$it: ${r.warnings}")
            assertTrue("line 2" in r.warnings.single(), "$it: ${r.warnings}")
        }
        val py = MaskingLexer.mask("a = 1\nb = \"\"\"never closed {\n}", PYTHON)
        assertEquals(visible("a = 1\nb = " + blank("\"\"\"never closed {\n}")), visible(py.text))
        assertTrue("line 2" in py.warnings.single(), "${py.warnings}")

        val kt = MaskingLexer.mask("val s = \"\"\"never closed {", KOTLIN)
        assertEquals(visible("val s = " + blank("\"\"\"never closed {")), visible(kt.text))
        assertTrue("line 1" in kt.warnings.single(), "${kt.warnings}")
    }

    @Test
    fun `a one-line string left open ends at its line break, with a warning`() =
        listOf(KOTLIN, JAVA, JS_TS, C_CPP, CSHARP, GO, SWIFT, PYTHON).forEach {
            val r = MaskingLexer.mask("s = \"open {\nf() {}", it)
            assertEquals(visible("s = " + blank("\"open {") + "\nf() {}"), visible(r.text), "$it")
            assertTrue("line 1" in r.warnings.single(), "$it: ${r.warnings}")
        }

    @Test
    fun `uncommented keeps literals and blanks only comments`() {
        val t = "val s = \"{\" // c {\n/* d */ val r = \"\"\"x\n}\"\"\""
        val r = MaskingLexer.mask(t, KOTLIN)
        assertEquals(visible("val s = \"{\" " + blank("// c {") + "\n" + blank("/* d */") + " val r = \"\"\"x\n}\"\"\""), visible(r.uncommented))
        val py = "x = '#' # note\n"
        assertEquals(visible("x = '#' " + blank("# note") + "\n"), visible(MaskingLexer.mask(py, PYTHON).uncommented))
        assertEquals("a // b", MaskingLexer.mask("a // b", Language.OTHER).uncommented)
    }

    @Test
    fun `a large file is masked in one pass`() {
        val line = "fun f(x: Int) { val s = \"{\" /* } */ // {\n"
        val text = line.repeat(50_000)
        val started = System.nanoTime()
        val out = MaskingLexer.mask(text, KOTLIN).text
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(text.length, out.length)
        assertTrue(ms < 2_000, "took $ms ms for ${text.length} chars")
    }

    @Test
    fun `line breaks inside multi-line literals are listed`() {
        val t = "def f():\n    \"\"\"Doc.\n\nMore.\n    \"\"\"\n    # a\n    # b\n    x = f\"\"\"a\n{1}\"\"\"\n"
        val m = MaskingLexer.mask(t, PYTHON)
        val inside = t.indices.filter { t[it] == '\n' && m.breakInLiteral(it) }
        assertEquals(listOf(t.indexOf("Doc.") + 4, t.indexOf("Doc.") + 5, t.indexOf("More.") + 5, t.indexOf("f\"\"\"a") + 5), inside)
        assertEquals(0, MaskingLexer.mask("val s = \"\"\"a\nb\"\"\"", KOTLIN).literalBreaks.size - 1)
        assertEquals(0, MaskingLexer.mask("// x\n/* a\nb */", KOTLIN).literalBreaks.size, "comments are not literals")
    }
}
