package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Python part 2g-2: classification and the declarations of a realistic file. */
@Timeout(10)
class PythonFinderTest {

    @Test
    fun `classify`() {
        mapOf(
            "class A(B, metaclass=M)" to Classified.Class("A"),
            "class A" to Classified.Class("A"),
            "class A[T]" to Classified.Class("A"),
            "def f(x)" to Classified.Function("f"),
            "def f[T](x: T) -> T" to Classified.Function("f"),
            "async def fetch(url)" to Classified.Function("fetch"),
            "def __init__(self)" to Classified.Function("__init__"),
            "if x" to Classified.Transparent,
            "else" to Classified.Transparent,
            "async with s() as t" to Classified.Transparent,
            "async for x in y" to Classified.Transparent,
            "match cmd" to Classified.Transparent,
            "except ImportError" to Classified.Transparent,
        ).forEach { (h, expected) -> assertEquals(expected, PythonFinder.classify(h), h) }
        listOf("class", "def", "classy", "", "x", "lambda").forEach { assertEquals(null, PythonFinder.classify(it), "'$it'") }
    }

    private val sample = javaClass.getResource("/fixtures/python/sample.py")!!.readText()

    @Test
    fun `a realistic file`() {
        val d = DeclarationFinder.find(sample, Language.PYTHON)
        assertEquals(
            listOf(
                "CLASS Shape d0 -", "FUNCTION area d1 Shape", "FUNCTION label d1 Shape", "CLASS Meta d1 Shape",
                "FUNCTION speed d0 -", "FUNCTION speed d0 -", "FUNCTION fetch d0 -", "FUNCTION one_liner d0 -", "CLASS Empty d0 -",
            ),
            d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" },
        )
        assertEquals(emptyList(), d.warnings)
        val s = d.items.map { it.start }
        assertEquals(
            listOf(
                sample.indexOf("# A shape."), sample.indexOf("def area"), sample.indexOf("@property"), sample.indexOf("class Meta"),
                sample.indexOf("def speed():\n        return 1"), sample.indexOf("def speed():\n        return 0"),
                sample.indexOf("async def fetch"), sample.indexOf("def one_liner"), sample.indexOf("class Empty"),
            ),
            s,
        )
        val e = d.items.map { it.end }
        assertEquals(sample.indexOf("[\"name\"]") + 7, e[0])
        assertEquals(sample.indexOf("helper()\n") + 7, e[1])
        assertEquals(sample.indexOf("[\"name\"]") + 7, e[3])
        assertEquals(sample.indexOf("s.get(url)") + 9, e[6])
        assertEquals(sample.indexOf("x * 2") + 4, e[7])
        assertEquals(sample.indexOf("class Empty: pass") + 16, e[8])
    }

    @Test
    fun `empty and declaration-free files`() {
        assertEquals(emptyList(), DeclarationFinder.find("", Language.PYTHON).items)
        assertEquals(emptyList(), DeclarationFinder.find("x = 1\nif x:\n    y = 2\n", Language.PYTHON).items)
    }

    @Test
    fun `functions inside functions and conditionals at class level`() {
        val t = "class K:\n    if DEBUG:\n        def dbg(self):\n            def inner():\n                pass\n"
        val d = DeclarationFinder.find(t, Language.PYTHON)
        assertEquals(listOf("CLASS K 0", "FUNCTION dbg 1"), d.items.map { "${it.kind} ${it.name} ${it.depth}" })
        assertEquals(0, d.items[1].parent)
    }
}
