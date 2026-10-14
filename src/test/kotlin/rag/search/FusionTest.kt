package rag.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FusionTest {

    @Test
    fun `spec acceptance - rrf of abc and cad gives a c b d`() {
        assertEquals(listOf("a", "c", "b", "d"), Fusion.rrf(listOf(listOf("a", "b", "c"), listOf("c", "a", "d"))).map { it.first })
    }

    @Test
    fun `rrf scores are summed reciprocal ranks`() {
        val scores = Fusion.rrf(listOf(listOf("a"), listOf("b", "a"))).toMap()

        assertEquals(1.0 / 61 + 1.0 / 62, scores["a"]!!, 1e-12)
        assertEquals(1.0 / 61, scores["b"]!!, 1e-12)
    }

    @Test
    fun `fts query quotes words so syntax is inert`() {
        assertEquals("\"slot\" OR \"table\" OR \"near\" OR \"x\"", FtsQuery.anyTerm("slot AND table NEAR(x) *"))
    }

    @Test
    fun `fts query drops stop words unless nothing else is left`() {
        assertEquals(listOf("framework", "decide"), FtsQuery.terms("How does the framework decide"))
        assertEquals(listOf("what", "is", "it"), FtsQuery.terms("What is it?"))
    }

    @Test
    fun `no words means no fts query`() {
        assertNull(FtsQuery.anyTerm("?! ..."))
    }
}
