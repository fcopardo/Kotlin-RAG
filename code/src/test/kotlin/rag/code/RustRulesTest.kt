package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** RustRules (plan step 2e-2). Inputs are masked: string contents are spaces. */
@Timeout(10)
class RustRulesTest {

    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    private fun shown(text: String, language: Language): Pair<List<String>, List<String>> {
        val d = DeclarationFinder.find(text, language)
        return d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" } to d.warnings
    }

    private val sample = javaClass.getResource("/fixtures/rust/sample.rs")!!.readText()

    @Test
    fun `types, traits and impl blocks`() {
        mapOf(
            "pub struct Point" to cls("Point"),
            "#[derive(Debug)] pub struct Point" to cls("Point"),
            "pub(crate) enum Cmd" to cls("Cmd"),
            "union Bits" to cls("Bits"),
            "pub trait Shape: Send + Sync" to cls("Shape"),
            "unsafe trait Raw" to cls("Raw"),
            "impl Point" to cls("impl Point"),
            "impl fmt::Display for Point" to cls("impl fmt::Display for Point"),
            "impl<T: fmt::Debug> Wrapper<T> where T: Clone," to cls("impl Wrapper<T>"),
            "impl<F: Fn() -> i32> Runner<F>" to cls("impl Runner<F>"),
            "unsafe impl Send for Raw" to cls("impl Send for Raw"),
            "mod parse" to Classified.Transparent,
            "#[cfg(test)] mod tests" to Classified.Transparent,
            "pub(super) mod inner" to Classified.Transparent,
        ).forEach { (h, expected) -> assertEquals(expected, RustRules.classify(h), h) }
    }

    @Test
    fun `functions`() {
        mapOf(
            "fn main()" to fn("main"),
            "pub const fn new(x: f64, y: f64) -> Self" to fn("new"),
            "pub(crate) fn point(s: &str) -> Option<Point>" to fn("point"),
            "pub async fn load(path: &Path) -> io::Result<String>" to fn("load"),
            "fn apply<F: Fn(i32) -> i32>(f: F, x: i32) -> i32" to fn("apply"),
            "#[test] fn parses()" to fn("parses"),
            "pub unsafe extern fn raw(p: *const u8)" to fn("raw"),
            "fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result" to fn("fmt"),
        ).forEach { (h, expected) -> assertEquals(expected, RustRules.classify(h), h) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf(
            "match self.0.clone()", "if x > 0", "else", "loop", "while let Some(x) = it.next()", "for x in xs", "unsafe",
            "let r = match v", "Move", "Some(x) =>", "|a: i32| -> i32", "macro_rules! square", "async", "impl", "",
        ).forEach { assertEquals(null, RustRules.classify(it), "'$it'") }
    }

    @Test
    fun `a realistic file`() {
        val (items, warnings) = shown(sample, Language.RUST)
        assertEquals(
            listOf(
                "CLASS Point d0 -", "CLASS impl Point d0 -", "FUNCTION new d1 impl Point", "FUNCTION dist d1 impl Point",
                "CLASS impl fmt::Display for Point d0 -", "FUNCTION fmt d1 impl fmt::Display for Point", "CLASS Shape d0 -",
                "FUNCTION describe d1 Shape", "CLASS Cmd d0 -", "FUNCTION point d0 -", "FUNCTION parses d0 -",
                "CLASS impl Wrapper<T> d0 -", "FUNCTION show d1 impl Wrapper<T>", "FUNCTION apply d0 -",
            ),
            items,
        )
        assertEquals(emptyList(), warnings)
        val d = DeclarationFinder.find(sample, Language.RUST).items.associateBy { it.name }
        assertEquals(sample.indexOf("/// A point"), d.getValue("Point").start)
        assertEquals(sample.indexOf("/// Creates"), d.getValue("new").start)
        assertEquals(sample.indexOf("#[test]"), d.getValue("parses").start)
        assertEquals(sample.indexOf("impl<T: fmt::Debug>"), d.getValue("impl Wrapper<T>").start)
    }
}
