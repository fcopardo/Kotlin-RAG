package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Java's rules (plan step 2c-2). */
@Timeout(10)
class JavaRulesTest {
    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    @Test
    fun `class-like headers`() {
        mapOf(
            "public final class Shapes" to cls("Shapes"),
            "class Box<T extends Comparable<T>> implements Iterable<T>" to cls("Box"),
            "interface Shape" to cls("Shape"),
            "public sealed interface Expr permits Num, Add" to cls("Expr"),
            "non-sealed class Num implements Expr" to cls("Num"),
            "enum Unit" to cls("Unit"),
            "record Circle(double r) implements Shape" to cls("Circle"),
            "public @interface Marker" to cls("Marker"),
            "@Deprecated( ) static abstract class Old" to cls("Old"),
        ).forEach { (h, expected) -> assertEquals(expected, JavaRules.classify(h), h) }
    }

    @Test
    fun `method and constructor headers`() {
        mapOf(
            "public Shapes add(Shape s)" to fn("add"),
            "@Override public String toString()" to fn("toString"),
            "@SuppressWarnings( ) public static <T extends Shape> double total(List<T> items)" to fn("total"),
            "default String describe()" to fn("describe"),
            "Rect(double w, double h)" to fn("Rect"),
            "public synchronized void put(K k, V v) throws InterruptedException" to fn("put"),
            "private static native long handle()" to fn("handle"),
        ).forEach { (h, expected) -> assertEquals(expected, JavaRules.classify(h), h) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf("static", "Circle", "CM(1)", "if (r < 0)", "else", "synchronized (lock)", "try", "finally", "do",
            "new Runnable()", "Runnable check = () ->", "for (T t : items)", "")
            .forEach { assertEquals(null, JavaRules.classify(it), "'$it'") }
    }

    @Test
    fun `no functions without braces`() {
        assertEquals(null, JavaRules.bodylessFunction("double area();"))
        assertEquals(null, JavaRules.bodylessFunction("int x = compute();"))
    }

    private val sample = javaClass.getResource("/fixtures/java/Sample.java")!!.readText()

    @Test
    fun `a realistic file`() {
        val d = DeclarationFinder.find(sample, Language.JAVA)
        assertEquals(
            listOf(
                "CLASS Shapes d0 -",
                "FUNCTION add d1 Shapes",
                "FUNCTION toString d1 Shapes",
                "FUNCTION total d1 Shapes",
                "CLASS Shape d1 Shapes",
                "FUNCTION describe d2 Shape",
                "CLASS Circle d1 Shapes",
                "FUNCTION area d2 Circle",
                "CLASS Rect d1 Shapes",
                "FUNCTION Rect d2 Rect",
                "FUNCTION area d2 Rect",
                "CLASS Unit d1 Shapes",
                "FUNCTION Unit d2 Unit",
                "CLASS Marker d1 Shapes",
            ),
            d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" },
        )
        assertEquals(emptyList(), d.warnings)
    }

    @Test
    fun `offsets in the realistic file`() {
        val items = DeclarationFinder.find(sample, Language.JAVA).items
        fun endOf(s: String) = sample.indexOf(s).also { require(it >= 0) { s } } + s.length - 1
        fun item(name: String, n: Int = 0) = items.filter { it.name == name }[n]
        assertEquals(sample.indexOf("/** Shapes"), item("Shapes").start)
        assertEquals(sample.length - 2, item("Shapes").end)
        assertEquals(sample.indexOf("/** Adds"), item("add").start)
        assertEquals(sample.indexOf("@Override\n    public String toString"), item("toString").start)
        assertEquals(endOf("this.h = h;\n        }"), item("Rect", 1).end)
        assertEquals(sample.indexOf("@Override\n        public double area"), item("area", 1).start)
        assertEquals(endOf("default \"\";\n    }"), item("Marker").end)
    }
}
