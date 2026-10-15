package rag.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import rag.SampleBook
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.store.SqliteStore
import java.io.File
import java.io.StringWriter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private lateinit var server: McpServer

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "rag.db").path)
        Indexer(store).index(SampleBook.write(File(dir, "sample.pdf")), embedder = FakeEmbedder())
        server = McpServer(RagService(store, { FakeEmbedder(model = it) }), "".reader().buffered(), StringWriter())
    }

    @AfterTest
    fun tearDown() = store.close()

    private fun send(message: String): JsonObject? = server.handle(message)

    private fun call(id: Int, tool: String, arguments: String) =
        send("""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")!!["result"]!!.jsonObject

    @Test
    fun `initialize answers with the client's protocol version when supported`() {
        val result = send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""")!!["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)

        val newer = send("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"2099-01-01"}}""")!!["result"]!!.jsonObject
        assertEquals(McpServer.PROTOCOL_VERSIONS.last(), newer["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools are listed with schemas, and notifications get no answer`() {
        assertNull(send("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val tools = send("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("search_books", "list_books", "list_chapters", "read_chapter"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertTrue(tools.all { "inputSchema" in it.jsonObject })
    }

    @Test
    fun `search returns passages as text and structured content`() {
        val result = call(1, "search_books", """{"query":"Text about delta on this page","k":1}""")

        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        val passage = result["structuredContent"]!!.jsonObject["passages"]!!.jsonArray.single().jsonObject
        assertEquals("Riding Fast", passage["chapter"]!!.jsonPrimitive.content)
        val text = Json.parseToJsonElement(result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(result["structuredContent"], text)
    }

    @Test
    fun `sloppy arguments from small models are read as meant`() {
        // As llama3.2:3b sends them: optional arguments as the string "null", numbers as strings.
        val result = call(1, "search_books", """{"query":"Text about delta on this page","k":"2","book_id":"null","subject":"null","mode":"hybrid"}""")

        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(2, result["structuredContent"]!!.jsonObject["passages"]!!.jsonArray.size)
    }

    @Test
    fun `tool failures are results with isError, protocol failures are errors`() {
        val missing = call(1, "read_chapter", """{"book_id":99,"chapter":1}""")
        assertEquals(true, missing["isError"]!!.jsonPrimitive.boolean)
        assertTrue("no book with id 99" in missing["content"].toString())
        assertEquals(true, call(2, "search_books", "{}")["isError"]!!.jsonPrimitive.boolean)

        assertEquals(-32601, send("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32700, send("not json")!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }
}
