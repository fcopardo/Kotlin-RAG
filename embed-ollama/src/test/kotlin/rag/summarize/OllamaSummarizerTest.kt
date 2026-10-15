package rag.summarize

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rag.RagException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** [OllamaSummarizer] against a fake Ollama; no model needed. */
class OllamaSummarizerTest {

    private val bodies = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private val sleeps = mutableListOf<Long>()
    private var server: HttpServer? = null

    @AfterTest
    fun stop() {
        server?.stop(0)
    }

    private fun fake(tags: String = """{"models":[{"name":"llama3.2:3b"}]}""", generate: (Int) -> Pair<Int, String>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/tags") { it.reply(200, tags) }
        s.createContext("/api/generate") { ex ->
            val call = synchronized(bodies) { bodies += Json.parseToJsonElement(ex.requestBody.readBytes().decodeToString()).jsonObject; bodies.size - 1 }
            val (status, body) = generate(call)
            ex.reply(status, body)
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

    private val task = SummaryTask(SummaryLevel.SECTION, "Book", "Chapter", listOf("Some text."), maxWords = 120)

    @Test
    fun `sends the v1 prompts and the spec's options`() {
        val url = fake { 200 to """{"response":"A summary."}""" }

        val text = OllamaSummarizer(baseUrl = url, sleep = { sleeps += it }).summarize(task)

        assertEquals("A summary.", text)
        val body = bodies.single()
        assertEquals("llama3.2:3b", body["model"]!!.jsonPrimitive.content)
        assertEquals(Prompts.SYSTEM, body["system"]!!.jsonPrimitive.content)
        assertContains(body["prompt"]!!.jsonPrimitive.content, "Summarize this passage in at most 120 words")
        val options = body["options"]!!.jsonObject
        assertEquals(0.0, options["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals(42, options["seed"]!!.jsonPrimitive.int)
        assertEquals(4096, options["num_ctx"]!!.jsonPrimitive.int)
        assertEquals(240, options["num_predict"]!!.jsonPrimitive.int)
    }

    @Test
    fun `empty answers are retried with backoff`() {
        val url = fake { call -> 200 to if (call < 2) """{"response":"  "}""" else """{"response":"Third time."}""" }

        assertEquals("Third time.", OllamaSummarizer(baseUrl = url, sleep = { sleeps += it }).summarize(task))
        assertEquals(listOf(1000L, 2000L), sleeps)
    }

    @Test
    fun `a missing model fails at once with the pull hint`() {
        val url = fake { 404 to """{"error":"model not found"}""" }

        val e = assertFailsWith<RagException.EmbeddingBackendUnavailable> { OllamaSummarizer(baseUrl = url, sleep = { sleeps += it }).summarize(task) }

        assertEquals(1, bodies.size)
        assertContains(e.message!!, "ollama pull llama3.2:3b")
    }

    @Test
    fun `availability check needs the exact model tag`() {
        val url = fake(tags = """{"models":[{"name":"llama3.2:1b"}]}""") { 200 to "{}" }

        val e = assertFailsWith<RagException.EmbeddingBackendUnavailable> { OllamaSummarizer(baseUrl = url).checkAvailable() }
        assertContains(e.message!!, "ollama pull llama3.2:3b")
        OllamaSummarizer(model = "llama3.2", baseUrl = url).checkAvailable() // any tag of an untagged name
    }

    @Test
    fun `group and book prompts list their inputs`() {
        val group = Prompts.user(SummaryTask(SummaryLevel.GROUP, "B", "C", listOf("first", "second"), 150))
        val book = Prompts.user(SummaryTask(SummaryLevel.BOOK, "B", null, listOf("One: a", "Two: b"), 250))

        assertContains(group, "1. first\n\n2. second")
        assertContains(book, "what the book covers and how it is organized")
    }
}
