package rag.embed

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs against a real local Ollama with both models pulled. Skipped unless RAG_OLLAMA=1 (SPEC §11). */
@Tag("ollama")
class OllamaIntegrationTest {

    private val url = System.getenv("RAG_OLLAMA_URL") ?: OllamaEmbedder.DEFAULT_URL
    private val nomic = OllamaEmbedder(EmbeddingModel.NOMIC_EMBED_TEXT, url)
    private val mxbai = OllamaEmbedder(EmbeddingModel.MXBAI_EMBED_LARGE, url)

    @BeforeTest
    fun requireOllama() {
        assumeTrue(System.getenv("RAG_OLLAMA") == "1", "set RAG_OLLAMA=1 to run Ollama integration tests")
        nomic.checkAvailable()
        mxbai.checkAvailable()
    }

    @Test
    fun `models return their native dimensions`() {
        assertEquals(768, nomic.dim)
        assertEquals(1024, mxbai.dim)
    }

    @Test
    fun `the default is mxbai-embed-large`() {
        assertEquals("mxbai-embed-large", OllamaEmbedder(baseUrl = url).model)
    }

    @Test
    fun `paraphrase is closer than an unrelated sentence`() {
        listOf(nomic, mxbai).forEach { embedder ->
            val query = embedder.embedQuery("bicycle speed")
            val (related, unrelated) = embedder.embedDocuments(listOf("The bike went really fast.", "The soup was cold."))

            assertTrue(Vectors.dot(query, related) > Vectors.dot(query, unrelated), embedder.model)
        }
    }
}
