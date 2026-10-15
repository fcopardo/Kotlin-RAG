package rag.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import rag.SampleBook
import rag.embed.FakeEmbedder
import rag.index.Indexer
import rag.store.SqliteStore
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpApiTest {
    @TempDir
    lateinit var dir: File
    private lateinit var store: SqliteStore
    private lateinit var api: HttpApi
    private val http = HttpClient.newHttpClient()

    @BeforeTest
    fun setUp() {
        store = SqliteStore(File(dir, "rag.db").path)
        Indexer(store).index(SampleBook.write(File(dir, "sample.pdf")), embedder = FakeEmbedder())
        api = HttpApi(RagService(store, { FakeEmbedder(model = it) }), "127.0.0.1", 0).also { it.start() }
    }

    @AfterTest
    fun tearDown() {
        api.close()
        store.close()
    }

    private fun get(path: String) = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${api.port}$path")).build(), HttpResponse.BodyHandlers.ofString())

    private fun post(path: String, body: String) = http.send(
        HttpRequest.newBuilder(URI("http://127.0.0.1:${api.port}$path")).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `search by GET returns full hits, by POST compact passages`() {
        val q = URLEncoder.encode("Text about delta on this page", Charsets.UTF_8)
        val full = get("/search?q=$q&k=1")
        assertEquals(200, full.statusCode())
        val hit = Json.parseToJsonElement(full.body()).jsonArray.single().jsonObject
        assertEquals("Riding Fast", hit["chapter"]!!.jsonObject["title"]!!.jsonPrimitive.content)

        val compact = post("/search", """{"query":"Text about delta on this page","k":1,"compact":true}""")
        assertEquals(200, compact.statusCode())
        assertEquals("p. 8", Json.parseToJsonElement(compact.body()).jsonArray.single().jsonObject["pages"]!!.jsonPrimitive.content)
    }

    @Test
    fun `books, chapters and chapter text`() {
        assertEquals(200, get("/health").statusCode())
        val book = Json.parseToJsonElement(get("/books").body()).jsonArray.single().jsonObject
        val id = book["id"]!!.jsonPrimitive.content
        assertEquals(4, Json.parseToJsonElement(get("/books/$id/chapters").body()).jsonArray.size)
        assertTrue("delta" in get("/books/$id/chapters/2").body())
    }

    @Test
    fun `errors are JSON with the right status`() {
        assertEquals(400, get("/search").statusCode())
        assertEquals(400, get("/search?q=bikes&k=abc").statusCode())
        assertEquals(400, post("/search", "{not json").statusCode())
        assertEquals(404, get("/books/99/chapters").statusCode())
        assertEquals(404, get("/nope").statusCode())
        assertTrue("error" in get("/nope").body())
    }
}
