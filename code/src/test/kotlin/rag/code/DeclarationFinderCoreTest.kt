package rag.code

import org.junit.jupiter.api.Timeout
import rag.code.DeclarationKind.CLASS
import rag.code.DeclarationKind.FUNCTION
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared part of [DeclarationFinder], with toy rules so no real language is involved:
 * `class X` is a class, `fn x(…)` a function, `fn x(…) = …` a function without braces,
 * `namespace` is transparent, `@` starts an annotation. Text is masked as Kotlin.
 */
@Timeout(10)
class DeclarationFinderCoreTest {

    private class ToyRules : FamilyRules {
        val headers = mutableListOf<String>()
        private val cls = Regex("""^(?:@\w+ )*class (\w+)""")
        private val fn = Regex("""^(?:@\w+ )*fn (\w+)\s*\(""")
        private val bodyless = Regex("""^fn (\w+)\s*\([^)]*\)\s*=""")

        override fun classify(header: String): Classified? {
            headers += header
            cls.find(header)?.let { return Classified.Class(it.groupValues[1]) }
            fn.find(header)?.let { return Classified.Function(it.groupValues[1]) }
            if (header.startsWith("namespace ")) return Classified.Transparent
            return null
        }

        override fun bodylessFunction(statement: String): String? = bodyless.find(statement)?.groupValues?.get(1)

        override val annotationPrefixes = listOf("@")
    }

    private fun find(text: String, rules: ToyRules = ToyRules()) = DeclarationFinder.find(text, Language.KOTLIN, rules)

    /** The declarations as `KIND name start..end depth parent`, which reads well when a test fails. */
    private fun shown(d: Declarations) = d.items.map { "${it.kind} ${it.name} ${it.start}..${it.end} d${it.depth} p${it.parent}" }

    private fun decl(kind: DeclarationKind, name: String, start: Int, end: Int, depth: Int = 0, parent: Int? = null) =
        "$kind $name $start..$end d$depth p$parent"

    /** Offset of the last character of the first occurrence of [s] (after [from]). */
    private fun String.endOf(s: String, from: Int = 0) = indexOf(s, from).also { require(it >= 0) { "not found: $s" } } + s.length - 1

    private fun String.at(s: String, from: Int = 0) = indexOf(s, from).also { require(it >= 0) { "not found: $s" } }

    @Test
    fun `a class and its function, with exact offsets`() {
        val t = "class A {\n    fn f() {\n        x()\n    }\n}\n"
        val d = find(t)
        assertEquals(
            listOf(
                decl(CLASS, "A", 0, t.lastIndexOf('}')),
                decl(FUNCTION, "f", t.at("fn f"), t.endOf("    }"), 1, 0),
            ),
            shown(d),
        )
        assertEquals(emptyList(), d.warnings)
    }

    @Test
    fun `nested classes nest, depth and parent follow`() {
        val t = "class A {\n  class B {\n    fn g() {}\n  }\n  fn h() {}\n}"
        assertEquals(
            listOf(
                decl(CLASS, "A", 0, t.length - 1),
                decl(CLASS, "B", t.at("class B"), t.endOf("  }\n  fn h") - "\n  fn h".length, 1, 0),
                decl(FUNCTION, "g", t.at("fn g"), t.endOf("fn g() {}"), 2, 1),
                decl(FUNCTION, "h", t.at("fn h"), t.endOf("fn h() {}"), 1, 0),
            ),
            shown(find(t)),
        )
    }

    @Test
    fun `nothing inside a function is emitted`() {
        val t = "fn outer() {\n  if (x) {\n    fn local() {}\n    class Local {}\n  }\n  list.each {\n    y()\n  }\n}"
        assertEquals(listOf(decl(FUNCTION, "outer", 0, t.length - 1)), shown(find(t)))
    }

    @Test
    fun `nothing inside a block that declares nothing is emitted`() {
        val t = "class A {\n  init {\n    fn hidden() {}\n  }\n  fn shown() {}\n}"
        assertEquals(
            listOf(decl(CLASS, "A", 0, t.length - 1), decl(FUNCTION, "shown", t.at("fn shown"), t.endOf("fn shown() {}"), 1, 0)),
            shown(find(t)),
        )
    }

    @Test
    fun `a transparent block is not a level`() {
        val t = "namespace N {\n  class A {\n    fn f() {}\n  }\n}\nfn top() {}"
        assertEquals(
            listOf(
                decl(CLASS, "A", t.at("class A"), t.endOf("  }\n}") - 2),
                decl(FUNCTION, "f", t.at("fn f"), t.endOf("fn f() {}"), 1, 0),
                decl(FUNCTION, "top", t.at("fn top"), t.length - 1),
            ),
            shown(find(t)),
        )
    }

    @Test
    fun `headers - brace on its own line, multi-line signatures, continuations, same-line boundaries`() {
        val t = """
            |class A
            |{
            |    fn multi(
            |        a,
            |        b
            |    ): Int {
            |        if (a) { b() } else { c() }
            |    }
            |    val p = 1
            |    fn afterProp() {}
            |    fn sig()
            |        : Int {
            |    }
            |    fn semi() { x(); y() }; fn second() {}
            |    class B {} class C {}
            |}
        """.trimMargin()
        val rules = ToyRules()
        val d = find(t, rules)
        assertTrue(
            rules.headers.containsAll(
                listOf("class A", "fn multi( a, b ): Int", "fn afterProp()", "fn sig() : Int", "fn semi()", "fn second()", "class B", "class C"),
            ),
            "headers seen: ${rules.headers}",
        )
        assertTrue(rules.headers.none { "val p" in it }, "a statement above is not part of a header: ${rules.headers}")
        assertEquals(listOf("A", "multi", "afterProp", "sig", "semi", "second", "B", "C"), d.items.map { it.name })
        assertEquals(t.at("fn multi"), d.items[1].start)
        assertEquals(t.endOf("c() }\n    }"), d.items[1].end)
    }

    @Test
    fun `braces in comments and literals don't count`() {
        val t = "class A {\n  val s = \"}\" // }\n  /* { */\n  fn f() { val t = '{' }\n}"
        assertEquals(
            // The block comment directly above `fn f` is its doc comment (step 5), braces and all.
            listOf(decl(CLASS, "A", 0, t.length - 1), decl(FUNCTION, "f", t.at("/* { */"), t.endOf("'{' }"), 1, 0)),
            shown(find(t)),
        )
    }

    @Test
    fun `a brace inside parentheses is not a body`() {
        val t = "fn f(cb = {}) {\n  x()\n}\nclass A {\n  fn g(a = { 1 }, b = 2) {\n  }\n}"
        val rules = ToyRules()
        assertEquals(
            listOf(
                decl(FUNCTION, "f", 0, t.endOf("x()\n}")),
                decl(CLASS, "A", t.at("class A"), t.length - 1),
                decl(FUNCTION, "g", t.at("fn g"), t.endOf("b = 2) {\n  }"), 1, 1),
            ),
            shown(find(t, rules)),
        )
        assertTrue("fn g(a = { 1 }, b = 2)" in rules.headers, "headers seen: ${rules.headers}")
    }

    @Test
    fun `doc comments and annotations directly above belong to the declaration`() {
        val t = """
            |class A {
            |    /**
            |     * Docs { with a brace }.
            |     */
            |    @Ann
            |    @Other(1)
            |    fn documented() {}
            |
            |    // not attached: a blank line follows
            |
            |    fn plain() {}
            |    // line doc
            |    fn lineDoc() {}
            |}
        """.trimMargin()
        val starts = find(t).items.associate { it.name to it.start }
        assertEquals(t.at("/**"), starts["documented"])
        assertEquals(t.at("fn plain"), starts["plain"])
        assertEquals(t.at("// line doc"), starts["lineDoc"])
        assertEquals(0, starts["A"])
    }

    @Test
    fun `functions without braces run to the next line indented no deeper`() {
        val t = """
            |class A {
            |    fn one() = 1
            |    fn two() =
            |        listOf(1, 2)
            |            .map { it * 2 }
            |
            |    fn three() = run {
            |        x()
            |    }
            |    fn four() {}
            |}
            |fn top() = 42
        """.trimMargin()
        val rules = ToyRules()
        assertEquals(
            listOf(
                decl(CLASS, "A", 0, t.endOf("fn four() {}\n}")),
                decl(FUNCTION, "one", t.at("fn one"), t.endOf("fn one() = 1"), 1, 0),
                decl(FUNCTION, "two", t.at("fn two"), t.endOf("it * 2 }"), 1, 0),
                decl(FUNCTION, "three", t.at("fn three"), t.endOf("x()\n    }"), 1, 0),
                decl(FUNCTION, "four", t.at("fn four"), t.endOf("fn four() {}"), 1, 0),
                decl(FUNCTION, "top", t.at("fn top"), t.length - 1),
            ),
            shown(find(t, rules)),
        )
        assertTrue(rules.headers.none { it.startsWith("fn three") || it.startsWith("fn two") }, "blocks inside a function without braces are not classified: ${rules.headers}")
    }

    @Test
    fun `a function without braces stops at a comment line, and its trailing comment is not part of it`() {
        val t = "class A {\n  fn a() = 1 // trailing\n  // doc for b\n  fn b() {}\n}"
        val items = find(t).items.associateBy { it.name }
        assertEquals(t.at("fn a"), items.getValue("a").start)
        assertEquals(t.endOf("fn a() = 1"), items.getValue("a").end)
        assertEquals(t.at("// doc for b"), items.getValue("b").start)
    }

    @Test
    fun `a function without braces never runs past its region's closing brace`() {
        val t = "class A {\nfn f() = 1\n   }\nfn g() {}"
        assertEquals(
            listOf(
                decl(CLASS, "A", 0, t.at("   }") + 3),
                decl(FUNCTION, "f", t.at("fn f"), t.endOf("fn f() = 1"), 1, 0),
                decl(FUNCTION, "g", t.at("fn g"), t.length - 1),
            ),
            shown(find(t)),
        )
    }

    @Test
    fun `an unclosed block closes at the end, with a warning`() {
        val t = "class A {\n  fn f() {\n  }\n\n"
        val d = find(t)
        val last = t.trimEnd().length - 1
        assertEquals(listOf(decl(CLASS, "A", 0, last), decl(FUNCTION, "f", t.at("fn f"), last, 1, 0)), shown(d))
        assertEquals(1, d.warnings.size, "${d.warnings}")
        assertTrue("line 1" in d.warnings.single() && "unclosed" in d.warnings.single(), "${d.warnings}")
    }

    @Test
    fun `an extra closing brace is ignored, with a warning`() {
        val t = "fn f() {}\n}\nfn g() {}"
        val d = find(t)
        assertEquals(listOf("f", "g"), d.items.map { it.name })
        assertTrue("line 2" in d.warnings.single() && "unmatched" in d.warnings.single(), "${d.warnings}")
    }

    @Test
    fun `the lexer's warnings come first`() {
        val t = "class A {\n}\n/* open"
        val d = find(t)
        assertEquals(listOf("A"), d.items.map { it.name })
        assertTrue("line 3" in d.warnings.first(), "${d.warnings}")
    }

    @Test
    fun `no rules means no declarations`() {
        val t = "class A {\n  fn f() {}\n}"
        assertEquals(emptyList(), DeclarationFinder.find(t, Language.KOTLIN, null).items)
        assertEquals(emptyList(), DeclarationFinder.find(t, Language.OTHER).items)
        assertEquals(emptyList(), find("").items)
    }

    @Test
    fun `items are sorted, nested inside their parent, and siblings never overlap`() {
        val t = """
            |namespace N {
            |  // doc
            |  class A {
            |    fn a1() { if (x) { y() } }
            |    fn a2() = 2
            |    class B {
            |      @Ann fn b1() {}
            |    }
            |  }
            |}
            |class C { fn c1() {} }
        """.trimMargin()
        checkShape(t, find(t))
        assertEquals(listOf("A", "a1", "a2", "B", "b1", "C", "c1"), find(t).items.map { it.name })
    }

    private fun checkShape(t: String, d: Declarations) {
        val items = d.items
        assertEquals(items.sortedBy { it.start }, items, "sorted by start")
        items.forEachIndexed { i, it ->
            assertTrue(it.start in 0..it.end && it.end < t.length, "range of #$i ${shown(d)[i]}")
            val p = it.parent
            if (p == null) {
                assertEquals(0, it.depth, "#$i")
            } else {
                assertTrue(p < i, "parent before child: #$i")
                assertEquals(items[p].depth + 1, it.depth, "#$i depth")
                assertTrue(it.start >= items[p].start && it.end <= items[p].end, "#$i inside its parent")
            }
        }
        items.groupBy { it.parent }.values.forEach { siblings ->
            siblings.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.start, "siblings overlap: $a / $b") }
        }
    }

    @Test
    fun `random soup never throws and keeps the shape`() {
        val alphabet = "{}()[];=\n  \"'/*@fnclass namespace xAb1"
        val random = Random(18)
        repeat(300) {
            val t = String(CharArray(random.nextInt(0, 300)) { alphabet[random.nextInt(alphabet.length)] })
            checkShape(t, find(t))
        }
    }

    @Test
    fun `a large file is scanned in linear time`() {
        val t = "class Big {\n" + (1..20_000).joinToString("") { "  fn f$it() {\n    x()\n  }\n" } + "}\n"
        val started = System.nanoTime()
        val d = find(t)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(20_001, d.items.size)
        assertTrue(ms < 3_000, "took $ms ms")
    }
}
