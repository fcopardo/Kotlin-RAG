package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** C#'s rules (plan step 2c-3). */
@Timeout(10)
class CSharpRulesTest {
    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    @Test
    fun `class-like headers and namespaces`() {
        mapOf(
            "namespace Rag.Sample" to Classified.Transparent,
            "public class Stack<T> where T : notnull" to cls("Stack"),
            "public sealed partial class View : Control" to cls("View"),
            "internal interface IShape" to cls("IShape"),
            "public struct Size" to cls("Size"),
            "public readonly struct Money" to cls("Money"),
            "public record Point(double X, double Y)" to cls("Point"),
            "public record struct Pair(int A, int B)" to cls("Pair"),
            "record class Person(string Name)" to cls("Person"),
            "public enum Color" to cls("Color"),
            "[Serializable] [DebuggerDisplay( )] public class Data" to cls("Data"),
        ).forEach { (h, expected) -> assertEquals(expected, CSharpRules.classify(h), h) }
    }

    @Test
    fun `methods, constructors and operators`() {
        mapOf(
            "public Stack(IEnumerable<T> initial)" to fn("Stack"),
            "public T Pop()" to fn("Pop"),
            "public bool TryPop(out T value)" to fn("TryPop"),
            "public static Stack<T> operator +(Stack<T> a, T b)" to fn("operator +"),
            "public static implicit operator int(Money m)" to fn("operator int"),
            "public override async Task<string> LoadAsync(CancellationToken ct = default)" to fn("LoadAsync"),
            "[HttpGet( )] public IActionResult Get(int id)" to fn("Get"),
            "static Config()" to fn("Config"),
            "public Child(int x) : base(x)" to fn("Child"),
            "public T Find<TKey>(TKey key) where TKey : IComparable<TKey>" to fn("Find"),
        ).forEach { (h, expected) -> assertEquals(expected, CSharpRules.classify(h), h) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf(
            "public int W", "public T this[int i]", "get", "set", "init", "if (items.Count == 0)", "else", "foreach (var x in xs)",
            "using (var r = Open())", "lock (gate)", "try", "catch (Exception e)", "finally", "Func<int, int> twice = x =>",
            "new Foo()", "switch (x)", "",
        ).forEach { assertEquals(null, CSharpRules.classify(it), "'$it'") }
    }

    @Test
    fun `expression-bodied members`() {
        mapOf(
            "public void Push(T item) => items.Add(item);" to "Push",
            "public override string ToString() =>" to "ToString",
            "public double Length() => Math.Sqrt(X * X + Y * Y);" to "Length",
            "public Point(int x) => X = x;" to "Point",
            "public static Money operator +(Money a, Money b) => new(a.V + b.V);" to "operator +",
            "T Get<TKey>(TKey k) where TKey : notnull => map[k];" to "Get",
        ).forEach { (s, expected) -> assertEquals(expected, CSharpRules.bodylessFunction(s), s) }
        listOf(
            "public int Count => items.Count;", "double Area();", "public T Pop()", "public abstract void Run();",
            "private readonly List<T> items = new();", "Func<int, int> f = x => x * 2;", "",
        ).forEach { assertEquals(null, CSharpRules.bodylessFunction(it), "'$it'") }
    }

    private val sample = javaClass.getResource("/fixtures/csharp/Sample.cs")!!.readText()

    @Test
    fun `a realistic file`() {
        val d = DeclarationFinder.find(sample, Language.CSHARP)
        assertEquals(
            listOf(
                "CLASS Stack d0 -",
                "FUNCTION Stack d1 Stack",
                "FUNCTION Push d1 Stack",
                "FUNCTION Pop d1 Stack",
                "FUNCTION TryPop d1 Stack",
                "FUNCTION operator + d1 Stack",
                "FUNCTION ToString d1 Stack",
                "CLASS Point d0 -",
                "FUNCTION Length d1 Point",
                "CLASS Size d0 -",
                "FUNCTION Area d1 Size",
                "CLASS IShape d0 -",
                "CLASS Color d0 -",
            ),
            d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" },
        )
        assertEquals(emptyList(), d.warnings)
    }

    @Test
    fun `offsets in the realistic file`() {
        val items = DeclarationFinder.find(sample, Language.CSHARP).items
        fun endOf(s: String) = sample.indexOf(s).also { require(it >= 0) { s } } + s.length - 1
        fun item(name: String, n: Int = 0) = items.filter { it.name == name }[n]
        assertEquals(sample.indexOf("/// <summary>A stack"), item("Stack").start)
        assertEquals(sample.indexOf("/// <summary>Pushes"), item("Push").start)
        assertEquals(endOf("items.Add(item);"), item("Push").end)
        assertEquals(sample.indexOf("[Obsolete"), item("Pop").start)
        assertEquals(endOf("string.Join(\", \", items);"), item("ToString").end)
        assertEquals(sample.indexOf("public Stack(IEnumerable"), item("Stack", 1).start)
        assertEquals(endOf("public enum Color { Red, Green }"), item("Color").end)
    }
}
