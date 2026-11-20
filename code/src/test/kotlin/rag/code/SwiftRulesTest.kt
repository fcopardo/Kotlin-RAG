package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** SwiftRules (plan step 2e-3). Inputs are masked: string contents are spaces. */
@Timeout(10)
class SwiftRulesTest {

    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    private fun shown(text: String, language: Language): Pair<List<String>, List<String>> {
        val d = DeclarationFinder.find(text, language)
        return d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" } to d.warnings
    }

    private val sample = javaClass.getResource("/fixtures/swift/Sample.swift")!!.readText()

    @Test
    fun `types and extensions`() {
        mapOf(
            "public struct Point: Equatable" to cls("Point"),
            "final class Circle: Shape" to cls("Circle"),
            "open class View" to cls("View"),
            "enum Direction: String" to cls("Direction"),
            "indirect enum Tree<T>" to cls("Tree"),
            "protocol Shape" to cls("Shape"),
            "actor Counter" to cls("Counter"),
            "@MainActor final class Model: ObservableObject" to cls("Model"),
            "extension Point: CustomStringConvertible" to cls("extension Point"),
            "extension Array where Element: Equatable" to cls("extension Array"),
            "public extension Collection" to cls("extension Collection"),
            "extension Dictionary<String, Int>" to cls("extension Dictionary"),
        ).forEach { (h, expected) -> assertEquals(expected, SwiftRules.classify(h), h) }
    }

    @Test
    fun `functions, initializers and subscripts`() {
        mapOf(
            "func area() -> Double" to fn("area"),
            "public func distance(to other: Point) -> Double" to fn("distance"),
            "static func + (lhs: Point, rhs: Point) -> Point" to fn("+"),
            "static func ==(lhs: A, rhs: A) -> Bool" to fn("=="),
            "@discardableResult class func unit() -> Circle" to fn("unit"),
            "mutating func flip()" to fn("flip"),
            "func map<T>(_ f: (Element) throws -> T) rethrows -> [T]" to fn("map"),
            "@available( ) public func old()" to fn("old"),
            "public init(x: Double, y: Double)" to fn("init"),
            "init?(r: Double)" to fn("init"),
            "required convenience init!(coder: NSCoder)" to fn("init"),
            "init<S: Sequence>(_ s: S)" to fn("init"),
            "deinit" to fn("deinit"),
            "subscript(i: Int) -> T" to fn("subscript"),
            "private(set) func reset()" to fn("reset"),
        ).forEach { (h, expected) -> assertEquals(expected, SwiftRules.classify(h), h) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf(
            "public var length: Double", "var opposite: Direction", "get", "set", "willSet", "didSet(old)", "switch self",
            "if let x = y", "guard r >= 0 else", "for i in 0..<n", "while true", "repeat", "do", "catch", "defer", "return",
            "class var shared: Model", "let f =", "",
        ).forEach { assertEquals(null, SwiftRules.classify(it), "'$it'") }
    }

    @Test
    fun `a realistic file`() {
        val (items, warnings) = shown(sample, Language.SWIFT)
        assertEquals(
            listOf(
                "CLASS Point d0 -", "FUNCTION init d1 Point", "FUNCTION distance d1 Point", "FUNCTION + d1 Point",
                "CLASS extension Point d0 -", "CLASS Shape d0 -", "CLASS Circle d0 -", "FUNCTION init d1 Circle",
                "FUNCTION deinit d1 Circle", "FUNCTION area d1 Circle", "FUNCTION unit d1 Circle", "CLASS Direction d0 -",
                "FUNCTION flip d1 Direction", "CLASS Counter d0 -", "FUNCTION increment d1 Counter", "FUNCTION makeCounter d0 -",
            ),
            items,
        )
        assertEquals(emptyList(), warnings)
        val d = DeclarationFinder.find(sample, Language.SWIFT).items.associateBy { it.name }
        assertEquals(sample.indexOf("/// A point"), d.getValue("Point").start)
        assertEquals(sample.indexOf("/// Distance"), d.getValue("distance").start)
        assertEquals(sample.indexOf("@discardableResult"), d.getValue("unit").start)
    }
}
