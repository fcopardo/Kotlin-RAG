package rag.embed

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rag.RagException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Tests [OllamaEmbedder] against a fake server; no real Ollama needed. */
class OllamaEmbedderTest {

    private val requests = mutableListOf<List<String>>()
    private val sleeps = mutableListOf<Long>()
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    /** Starts a fake Ollama. [embed] gets the call number (0-based) and inputs and returns (status, body). */
    private fun fakeOllama(
        tags: String = """{"models":[{"name":"nomic-embed-text:latest"}]}""",
        embed: (call: Int, inputs: List<String>) -> Pair<Int, String> = { _, inputs -> 200 to embeddings(inputs.size) },
    ): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/tags") { it.reply(200, tags) }
        s.createContext("/api/embed") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            assertEquals("nomic-embed-text", body["model"]!!.jsonPrimitive.content)
            val inputs = body["input"]!!.jsonArray.map { it.jsonPrimitive.content }
            val call = synchronized(requests) { requests += inputs; requests.size - 1 }
            val (status, response) = embed(call, inputs)
            exchange.reply(status, response)
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    /** [count] unnormalized 3-d vectors: (3, 4, 0) has length 5. */
    private fun embeddings(count: Int) =
        """{"model":"nomic-embed-text","embeddings":[${List(count) { "[3.0,4.0,0.0]" }.joinToString(",")}]}"""

    private fun embedder(url: String, batchSize: Int = 32) =
        OllamaEmbedder(EmbeddingModel.NOMIC_EMBED_TEXT, url, batchSize = batchSize, sleep = { sleeps += it })

    @Test
    fun `adds document and query prefixes`() {
        val e = embedder(fakeOllama())

        e.embedDocuments(listOf("The bike went fast."))
        e.embedQuery("how fast")

        assertEquals(listOf(listOf("search_document: The bike went fast."), listOf("search_query: how fast")), requests)
    }

    @Test
    fun `sends documents in batches and keeps order`() {
        val e = embedder(fakeOllama(), batchSize = 32)
        val texts = (1..70).map { "p$it" }

        val vectors = e.embedDocuments(texts)

        assertEquals(listOf(32, 32, 6), requests.map { it.size })
        assertEquals(texts.map { "search_document: $it" }, requests.flatten())
        assertEquals(70, vectors.size)
    }

    @Test
    fun `returns L2-normalized vectors`() {
        val v = embedder(fakeOllama()).embedQuery("x")

        assertEquals(listOf(0.6f, 0.8f, 0f), v.toList())
        assertEquals(1.0, sqrt(v.sumOf { (it * it).toDouble() }), 1e-5)
    }

    @Test
    fun `finds dimension from a probe embedding`() {
        assertEquals(3, embedder(fakeOllama()).dim)
    }

    @Test
    fun `retries server errors with exponential backoff`() {
        val url = fakeOllama { call, inputs -> if (call < 2) 500 to """{"error":"busy"}""" else 200 to embeddings(inputs.size) }

        val vectors = embedder(url).embedDocuments(listOf("a"))

        assertEquals(1, vectors.size)
        assertEquals(listOf(1000L, 2000L), sleeps)
    }

    @Test
    fun `gives up after three attempts`() {
        val url = fakeOllama { _, _ -> 503 to """{"error":"overloaded"}""" }

        val error = assertFailsWith<RagException.EmbeddingBackendUnavailable> { embedder(url).embedDocuments(listOf("a")) }

        assertEquals(5, error.exitCode)
        assertEquals(3, requests.size)
        assertContains(error.message!!, "overloaded")
    }

    @Test
    fun `does not retry a missing model`() {
        val url = fakeOllama { _, _ -> 404 to """{"error":"model \"nomic-embed-text\" not found, try pulling it first"}""" }

        val error = assertFailsWith<RagException.EmbeddingBackendUnavailable> { embedder(url).embedQuery("a") }

        assertEquals(1, requests.size)
        assertContains(error.message!!, "ollama pull nomic-embed-text")
    }

    @Test
    fun `rejects a response with the wrong number of embeddings`() {
        val url = fakeOllama { _, _ -> 200 to embeddings(1) }

        assertFailsWith<RagException.EmbeddingBackendUnavailable> { embedder(url).embedDocuments(listOf("a", "b")) }
    }

    @Test
    fun `availability check passes when the model is pulled`() {
        embedder(fakeOllama()).checkAvailable()
    }

    @Test
    fun `availability check explains how to pull a missing model`() {
        val url = fakeOllama(tags = """{"models":[{"name":"llama3:latest"}]}""")

        val error = assertFailsWith<RagException.EmbeddingBackendUnavailable> { embedder(url).checkAvailable() }

        assertContains(error.message!!, "ollama pull nomic-embed-text")
    }

    @Test
    fun `availability check reports an unreachable server`() {
        val freePort = ServerSocket(0).use { it.localPort }

        val error = assertFailsWith<RagException.EmbeddingBackendUnavailable> {
            embedder("http://127.0.0.1:$freePort").checkAvailable()
        }

        assertTrue("Cannot reach Ollama" in error.message!!)
    }
}
