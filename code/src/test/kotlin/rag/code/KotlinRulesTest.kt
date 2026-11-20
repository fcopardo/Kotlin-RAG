package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** Kotlin's rules (plan step 2b): headers, statements, and a whole file through [DeclarationFinder]. */
@Timeout(10)
class KotlinRulesTest {

    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    @Test
    fun `class-like headers`() {
        val cases = mapOf(
            "class Point" to cls("Point"),
            "data class Point(val x: Int, val y: Int)" to cls("Point"),
            "private sealed class Shape : Comparable<Shape>" to cls("Shape"),
            "enum class Color(val rgb: Int)" to cls("Color"),
            "annotation class Marker" to cls("Marker"),
            "interface Repo<T>" to cls("Repo"),
            "fun interface Action" to cls("Action"),
            "sealed interface Event" to cls("Event"),
            "@JvmInline value class Id(val v: Long)" to cls("Id"),
            "object Registry" to cls("Registry"),
            "companion object" to cls("Companion"),
            "companion object Factory : Creator<X>" to cls("Factory"),
            "private companion object : Creator<X>" to cls("Companion"),
            "internal abstract class Base<T : Any>(protected val x: T) : Parent(x), Iface" to cls("Base"),
            "inner class Node" to cls("Node"),
            "class `odd name`" to cls("odd name"),
        )
        cases.forEach { (header, expected) -> assertEquals(expected, KotlinRules.classify(header), header) }
    }

    @Test
    fun `function headers`() {
        val cases = mapOf(
            "fun add(a: Int, b: Int): Int" to fn("add"),
            "override suspend fun load(id: String): Result<User>" to fn("load"),
            "fun <T : Comparable<T>> List<T>.maxOrNull(): T?" to fn("maxOrNull"),
            "private inline fun <reified T> Any.cast(): T" to fn("cast"),
            "fun Map<String, List<Int>>.total(): Int" to fn("total"),
            "fun `parses a header`()" to fn("parses a header"),
            "@Test fun `x y`()" to fn("x y"),
            "@Suppress( ) @JvmStatic fun main(args: Array<String>)" to fn("main"),
            "@Deprecated(message = ) internal fun old()" to fn("old"),
            "constructor(x: Int) : this(x, 0)" to fn("constructor"),
            "operator fun plus(o: Point): Point" to fn("plus"),
        )
        cases.forEach { (header, expected) -> assertEquals(expected, KotlinRules.classify(header), header) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf(
            "init", "if (x)", "else", "when (y)", "try", "catch (e: Exception)", "finally", "for (i in 0..n)",
            "while (true)", "do", "list.forEach", "get()", "set(value)", "val x = object : Runnable",
            "object : Runnable", "val f = fun()", "x = run", "RED(0xFF0000)", "return@forEach", "",
        ).forEach { assertEquals(null, KotlinRules.classify(it), "'$it'") }
    }

    @Test
    fun `functions without braces`() {
        val cases = mapOf(
            "fun area() = w * h" to "area",
            "private fun sq(x: Int): Int = x * x" to "sq",
            "override fun toString(): String =" to "toString",
            "fun <T> id(x: T): T = x" to "id",
            "fun f(x: Int = 1, y: String = ) = x" to "f",
            "fun k(cb: () -> Unit = {}): Unit = cb()" to "k",
            "fun m(): (Int) -> Int = { it }" to "m",
            "fun eq(a: Int, b: Int): Boolean = a == b" to "eq",
            "fun <T : Comparable<T>> List<T>.maxOrNull(): T? =" to "maxOrNull",
            "@JvmStatic fun create() = Point(0, 0)" to "create",
            "fun `parse a list`(text: String): List<Point> = text.lines()" to "parse a list",
        )
        cases.forEach { (statement, expected) -> assertEquals(expected, KotlinRules.bodylessFunction(statement), statement) }
    }

    @Test
    fun `statements that are not functions without braces`() {
        listOf(
            "fun g(x: Int = 1) {", "fun h(): Int", "abstract fun label(): String", "fun n(a: Int): Boolean where T : X {",
            "val x = 1", "val f = fun() = 1", "fun interface A", "class A(val x: Int = 1)", "x == fun()",
            "constructor(side: Double) : this(side, side)", "fun cmp(a: Int, b: Int): Boolean {", "",
        ).forEach { assertEquals(null, KotlinRules.bodylessFunction(it), "'$it'") }
    }

    private val sample = javaClass.getResource("/fixtures/kotlin/Sample.kt")!!.readText()

    @Test
    fun `a realistic file`() {
        val d = DeclarationFinder.find(sample, Language.KOTLIN)
        val shown = d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" }
        assertEquals(
            listOf(
                "CLASS Point d0 -",
                "FUNCTION distanceTo d1 Point",
                "FUNCTION plus d1 Point",
                "CLASS Companion d1 Point",
                "FUNCTION of d2 Companion",
                "CLASS Shape d0 -",
                "FUNCTION describe d1 Shape",
                "CLASS Circle d1 Shape",
                "FUNCTION area d2 Circle",
                "CLASS Rect d1 Shape",
                "FUNCTION area d2 Rect",
                "CLASS Color d0 -",
                "CLASS Registry d0 -",
                "FUNCTION register d1 Registry",
                "FUNCTION maxOrNull d0 -",
                "FUNCTION parse a list d0 -",
                "FUNCTION main d0 -",
            ),
            shown,
        )
        assertEquals(emptyList(), d.warnings)
    }

    @Test
    fun `offsets in the realistic file`() {
        val items = DeclarationFinder.find(sample, Language.KOTLIN).items.associateBy { it.name }
        fun endOf(s: String) = sample.indexOf(s).also { require(it >= 0) { s } } + s.length - 1
        assertEquals(sample.indexOf("/** A point"), items.getValue("Point").start)
        assertEquals(sample.indexOf("}\n\nsealed interface"), items.getValue("Point").end)
        assertEquals(sample.indexOf("/** Distance"), items.getValue("distanceTo").start)
        assertEquals(endOf("Point(x + o.x, y + o.y)"), items.getValue("plus").end)
        assertEquals(sample.indexOf("@Synchronized"), items.getValue("register").start)
        assertEquals(endOf("if (a >= b) a else b }"), items.getValue("maxOrNull").end)
        assertEquals(endOf("Point.of(it) }"), items.getValue("parse a list").end)
        assertEquals(sample.indexOf("fun describe"), items.getValue("describe").start)
        assertEquals(endOf("with area \${area()}\""), items.getValue("describe").end)
    }
}
