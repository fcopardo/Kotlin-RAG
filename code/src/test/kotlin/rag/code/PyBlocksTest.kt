package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Python part 2g-1: indentation blocks. Each block prints as `<header> @<1-based line of its end>`. */
@Timeout(10)
class PyBlocksTest {
    private fun build(t: String): List<PyBlock> = PyBlocks.build(MaskingLexer.mask(t, Language.PYTHON), Lines(t))

    private fun tree(t: String): List<String> {
        val lines = Lines(t)
        val out = mutableListOf<String>()
        fun walk(bs: List<PyBlock>, d: Int) {
            for (b in bs) {
                val h = t.substring(b.header, b.colon).replace(Regex("\\s+"), " ").trim()
                out += "  ".repeat(d) + "$h @${lines.lineOf(b.end) + 1}"
                walk(b.children, d + 1)
            }
        }
        walk(build(t), 0)
        return out
    }

    private fun all(bs: List<PyBlock>): List<PyBlock> = bs.flatMap { listOf(it) + all(it.children) }

    @Test
    fun `nesting by indentation`() {
        val t = """
            |class A:
            |    x = 1
            |
            |    def f(self):
            |        return 1
            |
            |    def g(self, a,
            |          b):
            |        if a:
            |            pass
            |        return b
            |def h(): return 2
            |y = 3
            |""".trimMargin()
        assertEquals(listOf("class A @11", "  def f(self) @5", "  def g(self, a, b) @11", "    if a @10", "def h() @12"), tree(t))
        val bs = all(build(t))
        assertEquals(t.indexOf("return b") + 7, bs[0].end)
        assertEquals(t.indexOf("return 2") + 7, bs[4].end, "an inline body ends on its line")
        assertEquals(0, bs[0].indent)
        assertEquals(4, bs[1].indent)
        assertEquals(t.indexOf("def g"), bs[2].header)
        assertEquals(t.indexOf("b):") + 2, bs[2].colon)
    }

    @Test
    fun `docstrings and comments`() {
        val t = "def f():\n    \"\"\"Doc.\n\nNot a dedent.\n    \"\"\"\n    # trailing comment\n# column 0\nx = 1\n"
        assertEquals(listOf("def f() @5"), tree(t))
        assertEquals(t.indexOf("\"\"\"\n    # trailing") + 2, build(t)[0].end, "comments are not part of the body")
        val c = "class C:  # note\n    pass  # done\n"
        assertEquals(listOf("class C @2"), tree(c), "a comment after the colon is not an inline body")
        assertEquals(c.indexOf("pass") + 3, build(c)[0].end)
    }

    @Test
    fun `bracket and backslash continuations`() {
        val t = "if (a and\nb):\n    x = [1,\n2]\n    y = 1 + \\\n2\nz = 1\n"
        assertEquals(listOf("if (a and b) @6"), tree(t))
        assertEquals(t.indexOf("\\\n2") + 2, build(t)[0].end)
    }

    @Test
    fun `decorators belong to the block below`() {
        val t = """
            |@dataclass
            |class P:
            |    x: int = 0
            |
            |    @property
            |    @cache(
            |        size=3)
            |    def size(self) -> int:
            |        return 1
            |
            |@app.route("/")
            |async def index():
            |    return "hi"
            |""".trimMargin()
        assertEquals(listOf("class P @9", "  def size(self) -> int @9", "async def index() @13"), tree(t))
        val bs = all(build(t))
        assertEquals(listOf(0, t.indexOf("@property"), t.indexOf("@app.route")), bs.map { it.start })
        assertEquals(listOf(t.indexOf("class P"), t.indexOf("def size"), t.indexOf("async def")), bs.map { it.header })
        assertEquals('"', t[bs[2].end])
        assertEquals(t.indexOf("int:") + 3, bs[1].colon)
    }

    @Test
    fun `control flow, walrus, match, and colons that open nothing`() {
        val t = """
            |try:
            |    import fast
            |except ImportError:
            |    def fast(x):
            |        return x
            |else:
            |    pass
            |while (n := 3) > 0: n -= 1
            |if n := 4:
            |    pass
            |match cmd:
            |    case {"go": where}:
            |        go(where)
            |    case _:
            |        pass
            |lambda_x = lambda: 0
            |d = {1: 2}
            |x: int = 5
            |""".trimMargin()
        assertEquals(
            listOf(
                "try @2", "except ImportError @5", "  def fast(x) @5", "else @7", "while (n := 3) > 0 @8", "if n := 4 @10",
                "match cmd @15", "  case {\"go\": where} @13", "  case _ @15",
            ),
            tree(t),
        )
    }

    @Test
    fun `tabs move to the next multiple of 8`() {
        val t = "class T:\n\tdef f(self):\n\t\treturn 1\n        x = 2\n"
        assertEquals(listOf("class T @4", "  def f(self) @3"), tree(t))
        assertEquals(listOf(0, 8), all(build(t)).map { it.indent })
    }

    @Test
    fun `a block without a body ends on its header`() {
        val t = "def f():\nx = 1\nclass E:\n"
        assertEquals(listOf("def f() @1", "class E @3"), tree(t))
        assertEquals(t.indexOf(":\nx"), build(t)[0].end)
    }

    @Test
    fun `edge inputs never throw`() {
        listOf("", "\n", ":", "def", "if (:\n", "@\n", "@deco\n", "class A:\n    def f(:\n", "\\\n", "x = (\n", "\"\"\"\nopen").forEach {
            build(it)
        }
        assertEquals(emptyList(), tree("@deco\n"))
        val r = Random(7)
        val parts = listOf("def f():", "class A:", "    ", "\t", "\n", "(", ")", "\"\"\"", "#", ":", "@x", "\\", "if x:", " pass", "'")
        repeat(300) { build((1..40).joinToString("") { parts[r.nextInt(parts.size)] }) }
    }

    @Test
    fun `linear time`() {
        val unit = "class C:\n    @d\n    def f(self, a,\n          b):\n        \"\"\"doc\n        \"\"\"\n        if a:\n            return (b +\n 1)\n\n"
        val t = unit.repeat(4_000)
        val started = System.nanoTime()
        val bs = build(t)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(4_000, bs.size)
        assertTrue(ms < 2_000, "took $ms ms for ${t.length} chars")
    }
}
