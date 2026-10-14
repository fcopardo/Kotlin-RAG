package rag.embed

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs against a real local Ollama. Skipped unless RAG_OLLAMA=1 (SPEC §11). */
@Tag("ollama")
class OllamaIntegrationTest {

    private val embedder = OllamaEmbedder(baseUrl = System.getenv("RAG_OLLAMA_URL") ?: OllamaEmbedder.DEFAULT_URL)

    @BeforeTest
    fun requireOllama() {
        assumeTrue(System.getenv("RAG_OLLAMA") == "1", "set RAG_OLLAMA=1 to run Ollama integration tests")
        embedder.checkAvailable()
    }

    @Test
    fun `nomic-embed-text returns 768-dimensional vectors`() {
        assertEquals(768, embedder.dim)
    }

    @Test
    fun `paraphrase is closer than an unrelated sentence`() {
        val query = embedder.embedQuery("bicycle speed")
        val (related, unrelated) = embedder.embedDocuments(listOf("The bike went really fast.", "The soup was cold."))

        assertTrue(Vectors.dot(query, related) > Vectors.dot(query, unrelated))
    }
}
