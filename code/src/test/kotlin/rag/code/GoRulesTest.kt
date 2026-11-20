package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** GoRules (plan step 2e-1). Inputs are masked: string contents are spaces. */
@Timeout(10)
class GoRulesTest {

    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    private fun shown(text: String, language: Language): Pair<List<String>, List<String>> {
        val d = DeclarationFinder.find(text, language)
        return d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" } to d.warnings
    }

    private val sample = javaClass.getResource("/fixtures/go/sample.go")!!.readText()

    @Test
    fun `structs and interfaces`() {
        mapOf(
            "type Point struct" to cls("Point"),
            "type Shape interface" to cls("Shape"),
            "type Stack[T any] struct" to cls("Stack"),
            "type Pair[K comparable, V any] struct" to cls("Pair"),
            "Point struct" to cls("Point"),
        ).forEach { (h, expected) -> assertEquals(expected, GoRules.classify(h), h) }
    }

    @Test
    fun `functions and methods`() {
        mapOf(
            "func main()" to fn("main"),
            "func (p Point) Dist(q Point) float64" to fn("Dist"),
            "func (p *Point) Scale(k float64)" to fn("Scale"),
            "func (s *Stack[T]) Push(v T)" to fn("Push"),
            "func Map[T, U any](xs []T, f func(T) U) []U" to fn("Map"),
            "func (r *Repo) Find(ctx context.Context, id string) (*User, error)" to fn("Find"),
        ).forEach { (h, expected) -> assertEquals(expected, GoRules.classify(h), h) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf(
            "func()", "func (x int)", "if err != nil", "for _, x := range xs", "switch v := x.(type)", "select", "else",
            "var origin = Point", "Point", "map[string]int", "[]int", "type Alias = Point", "struct", "go func()", "",
        ).forEach { assertEquals(null, GoRules.classify(it), "'$it'") }
    }

    @Test
    fun `a realistic file`() {
        val (items, warnings) = shown(sample, Language.GO)
        assertEquals(
            listOf(
                "CLASS Point d0 -", "FUNCTION Dist d0 -", "FUNCTION Scale d0 -", "CLASS Shape d0 -", "CLASS Stack d0 -",
                "FUNCTION Push d0 -", "FUNCTION Map d0 -", "FUNCTION main d0 -",
            ),
            items,
        )
        assertEquals(emptyList(), warnings)
        val d = DeclarationFinder.find(sample, Language.GO).items.associateBy { it.name }
        assertEquals(sample.indexOf("// Point is"), d.getValue("Point").start)
        assertEquals(sample.indexOf("// Dist returns"), d.getValue("Dist").start)
        assertEquals(sample.indexOf("}\n\nfunc (p *Point) Scale"), d.getValue("Dist").end)
    }
}
