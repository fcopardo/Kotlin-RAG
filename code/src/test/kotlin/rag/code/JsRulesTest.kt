package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** JavaScript/TypeScript rules (plan step 2d). Inputs are masked: string contents are spaces. */
@Timeout(10)
class JsRulesTest {
    private fun cls(name: String) = Classified.Class(name)
    private fun fn(name: String) = Classified.Function(name)

    @Test
    fun `classes, interfaces, enums and namespaces`() {
        mapOf(
            "export class Vec" to cls("Vec"),
            "export default class App extends React.Component<Props>" to cls("App"),
            "abstract class Base implements Shape" to cls("Base"),
            "export abstract class Base" to cls("Base"),
            "export default class" to cls("default"),
            "export interface Props<T>" to cls("Props"),
            "enum Color" to cls("Color"),
            "export const enum Dir" to cls("Dir"),
            "const Comp = class extends Base" to cls("Comp"),
            "@Component( ) export class Card" to cls("Card"),
            "export namespace Geometry" to Classified.Transparent,
            "namespace A.B" to Classified.Transparent,
            "declare module" to Classified.Transparent,
            "declare global" to Classified.Transparent,
        ).forEach { (h, expected) -> assertEquals(expected, JsRules.classify(h), h) }
    }

    @Test
    fun `function declarations and methods`() {
        mapOf(
            "function add(a, b)" to fn("add"),
            "export async function load(path: string): Promise<Vec[]>" to fn("load"),
            "function* range(n: number)" to fn("range"),
            "export default function App()" to fn("App"),
            "function wrap<T>(x: T): T[]" to fn("wrap"),
            "constructor(public x: number, public y: number)" to fn("constructor"),
            "get length(): number" to fn("length"),
            "set length(v: number)" to fn("length"),
            "get(key: string)" to fn("get"),
            "static async *walk(node: Node)" to fn("walk"),
            "#tick()" to fn("#tick"),
            "private onChange<T>(e: T): void" to fn("onChange"),
            "add(o: Vec): Vec" to fn("add"),
            "@HostListener( ) onResize()" to fn("onResize"),
            "functionsOf(kind: string)" to fn("functionsOf"),
        ).forEach { (h, expected) -> assertEquals(expected, JsRules.classify(h), h) }
    }

    @Test
    fun `functions assigned to names`() {
        mapOf(
            "const handler = async (e: Event) =>" to fn("handler"),
            "export const fetchAll = async <T>(urls: string[]): Promise<T[]> =>" to fn("fetchAll"),
            "let f = function (x)" to fn("f"),
            "var g = function named(y)" to fn("g"),
            "private scaled = (k: number): Vec =>" to fn("scaled"),
            "handler: (e: Event) => void = (e) =>" to fn("handler"),
            "const g = x =>" to fn("g"),
            "onClick = () =>" to fn("onClick"),
        ).forEach { (h, expected) -> assertEquals(expected, JsRules.classify(h), h) }
    }

    @Test
    fun `blocks that declare nothing`() {
        listOf(
            "if (x)", "else if (x)", "for (const x of xs)", "while (true)", "switch (k)", "catch (e)", "try", "finally",
            "else", "do", "with (obj)", "const config =", "export default", "function (x)", "return (", "const x = foo(",
            "type Props =", "items.map((x) =>", "x =>", "const r = cond ?", "",
        ).forEach { assertEquals(null, JsRules.classify(it), "'$it'") }
    }

    @Test
    fun `arrow functions with an expression body`() {
        mapOf(
            "export const cross = (a: Vec, b: Vec) => a.x * b.y - a.y * b.x;" to "cross",
            "static zero = () => new Vec(0, 0);" to "zero",
            "export const sum = (vs: Vec[]): Vec =>" to "sum",
            "const double = x => x * 2;" to "double",
            "const id = <T>(x: T): T => x;" to "id",
            "const mk = () => ({ a: 1 });" to "mk",
            "const f: (x: number) => number = (x) => x + 1;" to "f",
            "export const isEven = async (n: number): Promise<boolean> => n % 2 === 0;" to "isEven",
        ).forEach { (s, expected) -> assertEquals(expected, JsRules.bodylessFunction(s), s) }
    }

    @Test
    fun `statements that are not functions without braces`() {
        listOf(
            "const h = () => {", "const r = items.map((x) => x * 2);", "function f() {", "export const x = 1;",
            "area(): number;", "bar: () => void;", "const fn = function (x) {", "name = ;", "abstract area(): number;",
            "if (a === b) return;", "x == y;", "",
        ).forEach { assertEquals(null, JsRules.bodylessFunction(it), "'$it'") }
    }

    private val sample = javaClass.getResource("/fixtures/ts/Sample.ts")!!.readText()

    @Test
    fun `a realistic file`() {
        val d = DeclarationFinder.find(sample, Language.JS_TS)
        assertEquals(
            listOf(
                "CLASS Vec d0 -",
                "FUNCTION constructor d1 Vec",
                "FUNCTION length d1 Vec",
                "FUNCTION add d1 Vec",
                "FUNCTION zero d1 Vec",
                "FUNCTION scaled d1 Vec",
                "FUNCTION toString d1 Vec",
                "CLASS Shape d0 -",
                "CLASS Base d0 -",
                "FUNCTION describe d1 Base",
                "CLASS Color d0 -",
                "FUNCTION dot d0 -",
                "FUNCTION cross d0 -",
                "FUNCTION load d0 -",
                "FUNCTION sum d0 -",
                "FUNCTION range d0 -",
            ),
            d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" },
        )
        assertEquals(emptyList(), d.warnings)
    }

    @Test
    fun `offsets in the realistic file`() {
        val items = DeclarationFinder.find(sample, Language.JS_TS).items.associateBy { it.name }
        fun endOf(s: String) = sample.indexOf(s).also { require(it >= 0) { s } } + s.length - 1
        assertEquals(sample.indexOf("/** A 2D"), items.getValue("Vec").start)
        assertEquals(sample.indexOf("/** Length"), items.getValue("length").start)
        assertEquals(endOf("static zero = () => new Vec(0, 0);"), items.getValue("zero").end)
        assertEquals(endOf("this.y * k);\n  }"), items.getValue("scaled").end)
        assertEquals(endOf("a.x * b.y - a.y * b.x;"), items.getValue("cross").end)
        assertEquals(endOf("acc.add(v), new Vec(0, 0));"), items.getValue("sum").end)
        assertEquals(sample.indexOf("export function dot"), items.getValue("dot").start)
    }
}
