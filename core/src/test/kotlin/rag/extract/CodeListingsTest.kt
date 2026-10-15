package rag.extract

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeListingsTest {

    private val listings = CodeListings()

    private fun CodeListings.Result.code() = codeRanges.map { text.substring(it.first, it.last + 1) }

    @Test
    fun `strips line numbers and merges a listing into one block`() {
        val page = "In code:\n\n1 @Composable\n\n2 fun Foo() {\n\n3 Text(\"hi\")\n\n4 }\n\nAfter the code."

        val result = listings.clean(page)

        assertEquals("In code:\n\n@Composable\nfun Foo() {\nText(\"hi\")\n}\n\nAfter the code.", result.text)
        assertEquals(listOf("@Composable\nfun Foo() {\nText(\"hi\")\n}"), result.code())
    }

    @Test
    fun `handles lines without blank lines between them and a listing at the page start`() {
        val result = listings.clean("10 style = x,\n11 \$composer\n\n12 )\nText after.")

        assertEquals(listOf("style = x,\n\$composer\n)"), result.code())
        assertEquals(0, result.codeRanges.single().first)
        assertEquals("style = x,\n\$composer\n)\n\nText after.", result.text)
    }

    @Test
    fun `keeps a wrapped code line inside the listing`() {
        val result = listings.clean("1 val x = someVeryLongCall(argumentOne,\nargumentTwo)\n\n2 val y = 2")

        assertEquals(listOf("val x = someVeryLongCall(argumentOne,\nargumentTwo)\nval y = 2"), result.code())
    }

    @Test
    fun `drops number-only lines, which are empty code lines`() {
        assertEquals(listOf("a()\nb()"), listings.clean("1 a()\n\n2\n\n3 b()").code())
    }

    @Test
    fun `leaves prose alone, including a single numbered line`() {
        val page = "Some text.\n\n2 reasons to care.\n\nMore text."

        val result = listings.clean(page)

        assertEquals(page, result.text)
        assertEquals(emptyList(), result.codeRanges)
    }

    @Test
    fun `numbers must be consecutive`() {
        assertEquals(emptyList(), listings.clean("1 first\n\n3 third").codeRanges)
    }

    @Test
    fun `finds two listings on one page`() {
        val result = listings.clean("1 a()\n2 b()\n\nBetween.\n\n1 c()\n2 d()")

        assertEquals(listOf("a()\nb()", "c()\nd()"), result.code())
    }
}
