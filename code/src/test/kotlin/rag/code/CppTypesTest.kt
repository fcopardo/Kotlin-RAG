package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** C/C++ part 2f-1: the header core, types and namespaces. Inputs are masked: string contents are spaces. */
@Timeout(10)
class CppTypesTest {
    private fun cls(name: String) = Classified.Class(name)

    @Test
    fun `the core drops templates, attributes and modifiers`() {
        mapOf(
            "template <typename T> class Box" to "class Box",
            "template <> void Box<int>::put(int v)" to "void Box<int>::put(int v)",
            "template <typename T, typename = std::enable_if_t<std::is_integral_v<T>>> T twice(T v)" to "T twice(T v)",
            "[[nodiscard]] constexpr int size() const" to "int size() const",
            "[[deprecated( )]] [[nodiscard]] int old()" to "int old()",
            "__attribute__((hot)) static void hot()" to "void hot()",
            "alignas(16) struct Vec4" to "struct Vec4",
            "inline namespace v1" to "namespace v1",
            "extern" to "extern",
            "extern int f(int x)" to "int f(int x)",
            "static const char* names[] =" to "const char* names[] =",
            "typedef struct Node" to "struct Node",
            "virtual ~Shape()" to "~Shape()",
            "explicit operator bool() const" to "operator bool() const",
            "" to "",
        ).forEach { (h, expected) -> assertEquals(expected, CppRules.core(h), h) }
    }

    @Test
    fun `types`() {
        mapOf(
            "struct Point" to cls("Point"),
            "class Circle final : public Shape" to cls("Circle"),
            "class Box" to cls("Box"),
            "struct hash<geo::Point>" to cls("hash"),
            "enum class Color : uint8_t" to cls("Color"),
            "enum struct Flags" to cls("Flags"),
            "enum Mode" to cls("Mode"),
            "union Value" to cls("Value"),
            "struct Node" to cls("Node"),
            "class Derived : public Base<int>, private Mixin" to cls("Derived"),
            "class Leaf final" to cls("Leaf"),
        ).forEach { (c, expected) -> assertEquals(expected, CppRules.typeHeader(c), c) }
    }

    @Test
    fun `namespaces and extern C blocks add no level`() {
        listOf("namespace geo", "namespace", "namespace a::b", "namespace v1", "extern")
            .forEach { assertEquals(Classified.Transparent, CppRules.typeHeader(it), it) }
    }

    @Test
    fun `a variable of a struct type is not a type`() {
        listOf("struct Point p =", "struct", "class", "enum", "struct stat st", "class Foo *ptr =", "union U u")
            .forEach { assertEquals(null, CppRules.typeHeader(it), "'$it'") }
    }

    @Test
    fun `classify routes type headers through the core`() {
        assertEquals(cls("Box"), CppRules.classify("template <typename T> class Box"))
        assertEquals(cls("Node"), CppRules.classify("typedef struct Node"))
        assertEquals(Classified.Transparent, CppRules.classify("inline namespace v1"))
        assertEquals(Classified.Transparent, CppRules.classify("extern"))
        assertEquals(null, CppRules.classify("struct Point p ="))
    }
}
