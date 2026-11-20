package rag.code

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals

/** C/C++ part 2f-2: function names, and a realistic file. Inputs are cores (see [CppRules.core]). */
@Timeout(10)
class CppFunctionsTest {

    @Test
    fun `functions, methods, constructors and destructors`() {
        mapOf(
            "double Point::length() const" to "Point::length",
            "Point(double x, double y) : x(x), y(y)" to "Point",
            "Circle(double r) : r_(r)" to "Circle",
            "~Circle() override" to "~Circle",
            "Shape::~Shape()" to "Shape::~Shape",
            "Foo::Foo()" to "Foo::Foo",
            "T clamp(T v, T lo, T hi)" to "clamp",
            "int add(int a, int b)" to "add",
            "int main(int argc, char** argv)" to "main",
            "int geo_version(void)" to "geo_version",
            "auto make() -> std::unique_ptr<Point>" to "make",
            "void Box<int>::put(int v)" to "Box::put",
            "std::vector<std::string> Parser::tokens(const std::string& s) const" to "Parser::tokens",
            "void draw() const noexcept override" to "draw",
            "int size() const" to "size",
            "const char* name() const" to "name",
            "void ::global_fn()" to "global_fn",
            "char *dup(const char *s)" to "dup",
            "std::map<int, std::string> Registry::names() const" to "Registry::names",
            "Point &Point::ref()" to "Point::ref",
        ).forEach { (c, expected) -> assertEquals(expected, CppRules.functionName(c), c) }
    }

    @Test
    fun `operators`() {
        mapOf(
            "bool operator==(const Point& o) const" to "operator==",
            "Point Point::operator+(const Point& o) const" to "Point::operator+",
            "bool operator()(int a, int b) const" to "operator()",
            "T& operator[](size_t i)" to "operator[]",
            "operator bool() const" to "operator bool",
            "std::ostream& operator<<(std::ostream& os, const Point& p)" to "operator<<",
            "Matrix<T>& Matrix<T>::operator*=(const Matrix<T>& o)" to "Matrix::operator*=",
        ).forEach { (c, expected) -> assertEquals(expected, CppRules.functionName(c), c) }
    }

    @Test
    fun `control flow, calls, lambdas and macros are not functions`() {
        listOf(
            "if (x)", "else if (x)", "if constexpr (N > 0)", "for (int i = 0; i < n; ++i)", "while (true)", "switch (c)",
            "catch (const std::exception& e)", "do", "else", "try", "return foo(x)", "auto f = [&](int x)",
            "std::vector<int> v", "TEST(Geometry, Length)", "foo(x)", "sizeof(Point)", "static_assert(sizeof(int) == 4)",
            "return std::pair<int, int> make(1, 2)", "public:", "", "new Point(1, 2)", "case 1:", "x.method(y)", "operatorX(1)", "const char* names[] =",
        ).forEach { assertEquals(null, CppRules.functionName(it), "'$it'") }
    }

    private val sample = javaClass.getResource("/fixtures/cpp/sample.cpp")!!.readText()

    @Test
    fun `a realistic file`() {
        val d = DeclarationFinder.find(sample, Language.C_CPP)
        assertEquals(
            listOf(
                "CLASS Point d0 -", "FUNCTION Point d1 Point", "FUNCTION operator== d1 Point", "FUNCTION Point::length d0 -",
                "CLASS Shape d0 -", "CLASS Circle d0 -", "FUNCTION Circle d1 Circle", "FUNCTION ~Circle d1 Circle",
                "FUNCTION area d1 Circle", "FUNCTION clamp d0 -", "CLASS Color d0 -", "FUNCTION geo_version d0 -",
                "FUNCTION main d0 -",
            ),
            d.items.map { "${it.kind} ${it.name} d${it.depth} ${it.parent?.let { p -> d.items[p].name } ?: "-"}" },
        )
        assertEquals(emptyList(), d.warnings)
        val items = d.items.associateBy { it.name }
        assertEquals(sample.indexOf("/// A point"), d.items.first { it.name == "Point" && it.kind == DeclarationKind.CLASS }.start)
        assertEquals(sample.indexOf("double Point::length"), items.getValue("Point::length").start)
        assertEquals(sample.indexOf("template <typename T>\nT clamp"), items.getValue("clamp").start)
        assertEquals(sample.indexOf("int geo_version"), items.getValue("geo_version").start)
        assertEquals(sample.indexOf("explicit Circle"), d.items.first { it.name == "Circle" && it.kind == DeclarationKind.FUNCTION }.start)
    }
}
