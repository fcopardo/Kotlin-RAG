package rag.embed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorsTest {

    @Test
    fun `normalizes to unit length`() {
        assertEquals(listOf(0.6f, 0.8f), Vectors.l2Normalize(floatArrayOf(3f, 4f)).toList())
    }

    @Test
    fun `leaves zero vector unchanged`() {
        assertEquals(listOf(0f, 0f), Vectors.l2Normalize(floatArrayOf(0f, 0f)).toList())
    }

    @Test
    fun `known model gets its prefixes, unknown model gets none`() {
        assertEquals("search_query: ", EmbeddingModel.forName("nomic-embed-text:latest").queryPrefix)
        assertEquals("", EmbeddingModel.forName("bge-m3").documentPrefix)
    }

    @Test
    fun `fake embedder ranks shared words higher`() {
        val fake = FakeEmbedder()
        val query = fake.embedQuery("how fast was the bike")
        val (bike, soup) = fake.embedDocuments(listOf("The bike went really fast.", "The soup was cold."))

        assertTrue(Vectors.dot(query, bike) > Vectors.dot(query, soup))
    }
}
