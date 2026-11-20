package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

@Timeout(10)
class MethodHeadersTest {
    private val java = JavaRules.CONTROL
    private val cs = CSharpRules.CONTROL

    private fun name(core: String, control: Set<String> = java) = MethodHeaders.parse(core, control)?.name

    @Test
    fun `methods with return types, generics and qualified types`() {
        assertEquals("of", name("public static <T> List<T> of(T... xs)"))
        assertEquals("run", name("void run()"))
        assertEquals("index", name("Map<String, List<Integer>> index(String s)"))
        assertEquals("names", name("java.util.List<String> names()"))
        assertEquals("total", name("public static <T extends Shape> double total(List<T> items)"))
        assertEquals("toString", name("String toString() throws IOException"))
        assertEquals("Hide", name("public new void Hide()", cs))
        assertEquals("LoadAsync", name("public async Task<int> LoadAsync(CancellationToken ct = default)", cs))
        assertEquals("Find", name("public T Find<TKey>(TKey key) where TKey : IComparable<TKey>", cs))
    }

    @Test
    fun `constructors - the name alone, with declared parameters or none`() {
        assertEquals("Point", name("Point(int x, int y)"))
        assertEquals("Point", name("Point()"))
        assertEquals("Foo", name("public Foo(@Nullable String s, final int n)"))
        assertEquals("Foo", name("Foo(@Named( ) String s, String... rest)"))
        assertEquals("Stack", name("Stack(IEnumerable<T> initial) : base(initial)", cs))
        assertEquals("Size", name("Size(int w = 0, [CallerMemberName] string n = )", cs))
    }

    @Test
    fun `calls, enum constants and control flow are not declarations`() {
        listOf(
            "foo(x)", "RED(1)", "INCH(2.54)", "if (x)", "else if (x)", "catch (IOException e)", "synchronized (lock)",
            "for (int i = 0; i < n; i++)", "switch (x)", "try (var r = open())", "while (true)", "new Runnable()",
            "return foo(x)", "throw new E(m)", "Runnable r = () -> foo()", "list.forEach(x -> foo(x))", "this.run()",
            "static", "", "int[] a = new int[n]",
        ).forEach { assertEquals(null, name(it), "'$it'") }
        listOf("foreach (var x in xs)", "using (var r = Open())", "lock (o)", "Action a = () => Run()", "await Task.Run(f)", "x => Run(x)")
            .forEach { assertEquals(null, name(it, cs), "'$it'") }
    }

    @Test
    fun `the parameter list's offsets`() {
        // v0 o1 i2 d3 ' '4 a5 (6 i7 n8 t9 ' '10 x11 )12
        assertEquals(MethodHeader("a", 6, 12), MethodHeaders.parse("void a(int x) throws E", java))
        val nested = "void b(Runnable r = () -> {}, int[] xs)"
        assertEquals(MethodHeader("b", nested.indexOf('('), nested.lastIndexOf(')')), MethodHeaders.parse(nested, java))
    }

    @Test
    fun `matching counts all bracket kinds together`() {
        val s = "(a[b{c}d](e))x"
        assertEquals(12, MethodHeaders.matching(s, 0))
        assertEquals(8, MethodHeaders.matching(s, 2))
        assertEquals("(never".length - 1, MethodHeaders.matching("(never", 0))
    }
}
